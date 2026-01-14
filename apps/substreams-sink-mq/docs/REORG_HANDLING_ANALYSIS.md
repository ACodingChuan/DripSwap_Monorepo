# Blockchain Reorg Handling – Queue-Centric Analysis

**Date**: 2025-09-24 (living document)  
**Scope**: Substreams → MQ sinks (NATS / Redpanda)  
**Status**: DESIGN ITERATION IN PROGRESS

---

## 🎯 Executive Summary

Our sinks stream Substreams output directly into message queues without
maintaining their own stateful store. To survive blockchain reorgs we must emit
enough context for *downstream* consumers (databases, stream processors, data
lakes) to deterministically undo and replay. The sink stays stateless; the queue
becomes the canonical change log.

Core decisions:

- **Combined payload** – every routed message bundles business data *and* history
  metadata so state and undo context never diverge.
- **Explicit undo control** – upon `BlockUndoSignal` the sink emits a control
  record describing the rollback boundary.
- **Consumer responsibility** – downstream systems pause, rollback everything
  with `block_number > last_valid`, and resume consuming when ready. No extra
  “resume” control is required; the next data message from the sink is already
  the canonical replacement block.
- **Stateless sink** – we never generate reversal mutations ourselves; we simply
  forward Substreams events with consistent metadata.

---

## 🧠 Understanding the Signals

Substreams exposes three relevant gRPC messages:

| Signal                | Meaning                                                  | Sink action                                  |
|-----------------------|----------------------------------------------------------|----------------------------------------------|
| `BlockScopedData`     | Normal block output with module responses                | Emit combined payload record(s)              |
| `BlockUndoSignal`     | Longer chain discovered; everything after `last_valid` should be rolled back | Emit undo control message, pause new data until replay begins |
| `BlockProgress`       | Heartbeat/monitoring                                     | Surface as metrics/logs (optional)           |

Once an undo is acknowledged Substreams replays replacement blocks starting at
`last_valid + 1`. The first new `BlockScopedData` on the stream is therefore the
implicit resume signal.

---

## 🧩 Combined Payload Contract

### Why a combined record?

- **Atomicity** – downstream consumers receive state change and historical context
  together, eliminating drift.
- **Idempotency** – primary keys, block numbers and ordinals serve as composite
  identifiers to deduplicate on replay.
- **Portability** – any backend (SQL, NoSQL, stream processor) can rely on the
  same JSON envelope.

### Envelope structure (conceptual)

```json
{
  "kind": "data",
  "topic": "spl.token.transfers",
  "block": {
    "number": 123456,
    "hash": "0xabc...",
    "parent_hash": "0xdef...",
    "cursor": "2:123456:45",
    "ordinal": 45,
    "timestamp": "2025-09-24T12:34:56Z",
    "step": "STEP_NEW"
  },
  "module": {
    "name": "map_spl_instructions",
    "type_url": "type.googleapis.com/sf.solana.spl.v1.type.SplInstructions"
  },
  "payload": {
    "data": {
      "message_type": "transfer",
      "fields": { "...": "..." }
    },
    "history": {
      "operation_type": "INSERT",
      "primary_key": { "instruction_id": "…" },
      "previous_data": null,
      "published_at": "2025-09-24T12:34:57Z"
    }
  }
}
```

Downstream consumers can reconstruct tables, materialized views, or event logs
strictly from this envelope.

---

## 🚨 Control Messaging

- **Trigger**: arrival of `BlockUndoSignal { last_valid_block, last_valid_cursor }`.
- **Sink reaction** (on the *same* subject/topic used for data):
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
- After publishing the control record, pause new `data` messages until Substreams
  begins replaying canonical blocks.
- **Resume** is implicit—consumers treat the next `kind: "data"` record as the
  start of replay.

Keeping control and data on the same stream preserves ordering guarantees and
prevents drift between the signals.

---

## 🧾 Downstream Playbook

1. **Consume data + control** streams.
2. **Persist** messages with block metadata (`block.number`, `ordinal`) to allow
   deterministic rebuilds.
3. **On undo control**:
   - Halt application of further data until rollback completes.
   - Delete or revert any rows/events where `block_number > last_valid`.
   - Reset local cursor to the supplied value (if maintaining offsets).
4. **Resume** consumption; the next data record will correspond to
   `last_valid + 1`.

Because history metadata travels with each message, consumers can reconstruct
previous state even if they store only append-only logs.

---

## 🛣️ Milestones

| Milestone | Goal | Scope |
|-----------|------|-------|
| **M0 – Contract Finalisation** | Lock the JSON envelope and control message schema. | Update docs, publish schema fixtures, align sink logging. |
| **M1 – Metadata Enrichment** | Emit block metadata + history payload in both sinks. | Router/serializer changes, regression tests. |
| **M2 – Control Channel** | Publish undo control records and pause publication while replay pending. | Sink state machine, metrics, feature flag. |
| **M3 – Downstream Tooling** | Provide reference consumers / SDK helpers (external repo). | Sample Rust/Python workers, docs. |
| **M4 – Hardening** | Deep reorg guardrails, observability, ops guidance. | Metrics, alerts, operational handbook. |

---

## ⚖️ Trade-offs & Risks

- **Pros**: stateless sink, portable contract, consumers retain autonomy, no
  tight coupling to any database.
- **Cons**: downstream systems must implement rollback logic; they must store
  enough history to replay. Deep reorgs still require careful tuning.
- **Mitigations**: provide SDK patterns, emit clear metrics (undo counts, pause
  durations), document best practices for retention and dedupe.

---

## 📌 Open Questions

1. **History richness** – is `previous_data` always available? For inserts we may
   omit it; for updates/deletes we might—conditionally—include prior values from
   Substreams output if the upstream package provides them.
2. **Max undo depth** – should sinks enforce a safety cutoff (e.g. pause + alert
   beyond 256 blocks)?
3. **Partitioning strategy** – how do we ensure a single ordering domain for
   mixed data/control streams (single partition vs coordinated fanout)?
4. **Multi-sink deployments** – leader election or coordination required if more
   than one replica publishes to the same queue?

Contributions welcome—update this analysis as design questions are answered or
new risks surface.
