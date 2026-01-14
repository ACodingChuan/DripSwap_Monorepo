# Blockchain Reorg Handling – Technical Specification

**Document Version**: 1.0 (draft)  
**Last Updated**: 2025-09-24  
**Scope**: Stateless Substreams → MQ sinks (NATS JetStream, Redpanda)

---

## Contents
1. [Architecture Overview](#architecture-overview)
2. [Message Contract](#message-contract)
3. [Control Channel](#control-channel)
4. [Sink Behaviour](#sink-behaviour)
5. [Downstream Responsibilities](#downstream-responsibilities)
6. [Configuration Model](#configuration-model)
7. [Observability](#observability)
8. [Error Handling](#error-handling)
9. [Testing Strategy](#testing-strategy)
10. [Open Questions](#open-questions)

---

## Architecture Overview
```
Substreams ──► MQ Sink (NATS/Redpanda)
                │
                └── single subject/topic per routed stream
                        • combined payload records
                        • undo control records (kind="undo")
```

Principles:
- **Stateless sink** – we forward Substreams output; no local database.
- **Combined payload** – data and history metadata live in the same JSON record.
- **Explicit undo** – `BlockUndoSignal` is translated into a queue control message.
- **Consumer-driven replays** – downstream systems implement rollback using the metadata we emit.

---

## Message Contract

Each logical record emitted by the sink must follow the schema below. Field
order is illustrative; JSON keys are canonicalised by the serializer in code.

```json
{
  "kind": "data",
  "topic": "spl.token.transfers",
  "partition_key": "spl.token.transfers",
  "block": {
    "number": 123456,
    "hash": "0xabc...",
    "parent_hash": "0xdef...",
    "cursor": "2:123456:45",
    "ordinal": 45,
    "step": "STEP_NEW",
    "timestamp": "2025-09-24T12:34:56Z"
  },
  "module": {
    "name": "map_spl_instructions",
    "type_url": "type.googleapis.com/sf.solana.spl.v1.type.SplInstructions"
  },
  "payload": {
    "data": { "... business fields ..." },
    "history": {
      "operation_type": "INSERT",
      "primary_key": { "instruction_id": "…" },
      "previous_data": null,
      "published_at": "2025-09-24T12:34:57Z"
    }
  }
}
```

Implementation notes:
- `partition_key` is used by Redpanda/Kafka; for NATS it can be omitted or set
  to the subject.
- `history.previous_data` is optional. When the upstream Substreams module
  exports the previous state we include it; otherwise it may be `null`.
- The sink must guarantee deterministic ordering per block via `ordinal`.
- The serializer is responsible for removing any protobuf artifacts (e.g.
  camelCase vs snake_case) so the payload is idiomatic JSON.

---

## Control Records

Control records use the same subject/topic as the corresponding data messages
and rely on `kind = "undo"` to distinguish them:

```json
{
  "kind": "undo",
  "block": {
    "last_valid": 123450,
    "cursor": "2:123450:10"
  },
  "metadata": {
    "received_at": "2025-09-24T12:45:00Z",
    "reason": "chain_reorg"
  }
}
```

Rules:
- Publish exactly one control record per `BlockUndoSignal`.
- Do **not** emit a separate `resume` message; the next `kind:"data"` record
  implies resumption.
- Ensure control publication is synchronous with the sink state change (see next
  section).

---

## Sink Behaviour

State machine:

```
Starting ─► Streaming ──(BlockUndoSignal)──► Undoing ──(first replayed block)──► Streaming
```

Implementation requirements:

1. **Streaming** state
   - Parse each `BlockScopedData` and emit combined payload records.
   - Maintain current block metadata for logging/metrics.

2. **On undo signal**
   - Transition to **Undoing**.
   - Publish control record.
   - Pause publication of further `data` messages until Substreams delivers the
     next canonical block.

3. **During Undoing**
   - Buffer or drop incoming `BlockScopedData` (implementation choice) but do
     not emit them.
   - Keep heartbeats/metrics so operators can observe the pause.

4. **First canonical block after undo**
   - Transition back to **Streaming**.
   - Emit combined payload records as usual.

Concurrency guidelines:
- Ensure only one control record is published per undo even with concurrent
  tasks.
- Provide backpressure handling so the sink doesn’t overwhelm the queue when
  replay bursts occur.

---

## Downstream Responsibilities

Consumers must:
1. Subscribe to the routed subject/topic and inspect `kind` on each record.
2. Persist or index data records with block metadata (`block.number`,
   `ordinal`).
3. Upon receiving `kind = "undo"`:
   - Pause consuming/applying new data until rollback is complete.
   - Remove or revert any state derived from records where
     `block.number > last_valid`.
   - Optionally reset their own cursor/offset to the provided value.
4. Resume consumption; the next data record is the canonical continuation.

We will publish reference helpers (outside this repository) showing rollback
implementations for SQL databases, key/value stores, and stream processors.

---

## Configuration Model

Environment variables / config keys:

| Option | Description | Default |
|--------|-------------|---------|
| `REORG_CONTROL_ENABLED` | Feature flag for control publication | `false` (until GA) |
| `REORG_MAX_UNDO_DEPTH` | Safety limit; pause + alert if exceeded | `256` blocks |
| `REORG_UNDO_TIMEOUT_SECS` | Alert if replay takes longer than this | `60` seconds |
| `REORG_METRICS_ENABLED` | Emits Prometheus counters/gauges | `true` |

Both sinks share the same configuration parser in `substreams-core`.

---

## Observability

Metrics to expose (labels: `sink`, `backend`):
- `reorg_control_messages_total{kind="undo"}` – count of undo signals published.
- `reorg_paused_seconds_total` – cumulative time spent in `Undoing`.
- `reorg_last_valid_block` – gauge updated from control records.
- `reorg_events_dropped_total` – number of data messages dropped during undo (if
  buffering is not implemented).

Logs:
- Info log for every control record with block numbers.
- Warning when undo depth approaches `REORG_MAX_UNDO_DEPTH`.
- Error for publish failures (with retries/backoff details).

Tracing (optional):
- Span `reorg.pause` covering the duration between undo and first replayed block.

---

## Error Handling

| Scenario | Behaviour |
|----------|-----------|
| Control publish failure | Retry with exponential backoff up to `REORG_UNDO_TIMEOUT_SECS`. If exhausted, surface critical error and halt to avoid inconsistent state. |
| Replay exceeds `REORG_MAX_UNDO_DEPTH` | Stop streaming, emit alert, require human intervention. |
| Sink restart mid-undo | On restart, re-subscribe to Substreams; first signal will be either an in-flight undo (publish again) or the replayed block. Control message publication must be idempotent. |
| Buffer overflow while paused | Drop buffered data, increment `reorg_events_dropped_total`, log warning. Consumers rely on replay for canonical data. |

---

## Testing Strategy

1. **Unit tests**
   - Envelope serialization (data + history).
   - Control message builder.
   - State machine transitions.

2. **Integration tests**
   - Local NATS/Redpanda harness with mock Substreams stream that emits
     `BlockScopedData` + `BlockUndoSignal`.
   - Assert ordering, control publication, and pause behaviour.

3. **End-to-end replay test**
   - Simulate consumer that records data, receives undo, and verifies rollback
     using metadata.

4. **Chaos tests**
   - Rapid successive undos (shallow).
   - Deep undo near max depth.
   - Sink restarts during undo.

Testing utilities and fixtures should live alongside the sinks, while consumer
integration tests belong in the `substreams-sink-mq-integrations` repository.

---

## Open Questions

1. **Previous data availability** – when the upstream module cannot provide prior
   values, should we mark `history.previous_data` explicitly as `null` or omit
   it?
2. **Buffering strategy** – is it acceptable to drop all data received during
   undo, relying on replay, or do we need an optional bounded buffer?
3. **Multiple sink replicas** – do we support active/active publishing, or
   require a single leader to avoid duplicate control messages?
4. **Schema evolution** – how do we version the combined payload without
   breaking existing consumers? (Proposal: embed a `contract_version` field with
   semantic versioning.)
5. **Control channel security** – do we need ACLs/subject-level auth to prevent
   accidental writes by other services?

Feedback and PRs welcome—update this specification as implementation details
solidify.
