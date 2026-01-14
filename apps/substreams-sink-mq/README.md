# Substreams Message Queue Sinks

A production-ready toolkit for streaming Substreams output into message queues. The repository contains a shared core library plus two specialized sinks: NATS JetStream and Kafka/Redpanda. Fanout routing, protobuf analysis, and deployment automation are delivered through a unified Python toolchain.

## Highlights
- Separated sink implementations for NATS and Redpanda/Kafka
- Universal protobuf analysis with semantic type support
- Dynamic fanout routing and topic/subject generation
- Integration-ready message envelopes for downstream consumers
- Comprehensive Python and Rust test suites

## Architecture
```
Substreams API → Specialized Sink → Message Queue
```
Core crates:
- `substreams-core/`: dynamic router, protobuf utilities, shared logic
- `substreams-nats-sink/`: JetStream client with dot-notation subjects
- `substreams-kafka-sink/`: Kafka/Redpanda producer with topic routing

Key scripts (all Python):
- `scripts/setup_universal_pipeline.py`: end-to-end fanout pipeline bootstrap
- `scripts/analyze_protobuf_schema.py`: schema + semantic type inspection
- `scripts/nats_stream_manager.py` / `scripts/kafka_topic_manager.py`: infrastructure provisioning

## Prerequisites
- Docker & Docker Compose
- Python 3.10+ with [`uv`](https://github.com/astral-sh/uv)
- Rust 1.85+ (edition 2024)
- Substreams API token from StreamingFast (store in `secrets/substreams_token.txt`)

## Getting Started
```bash
git clone <repo-url>
cd substreams-sink-mq
uv venv .venv
source .venv/bin/activate
uv pip install -r requirements.txt
```
Put your Substreams token at `secrets/substreams_token.txt` (not tracked by git).

### Bootstrap a Pipeline
Use the combined setup command to analyze protobufs, generate fanout configuration, and optionally create queue resources.

```bash
# Backend = nats | kafka
docker compose -f docker-compose-<backend>.yaml --profile <profile> up -d
uv run scripts/setup_universal_pipeline.py <backend> path/to/schema.proto --deploy
```
`--deploy` provisions NATS or Kafka resources. Omit the flag to inspect generated files under `configs/`.

### Manual Steps (if needed)
```bash
uv run scripts/analyze_protobuf_schema.py path/to/schema.proto configs/generated-analysis.json
uv run scripts/nats_stream_manager.py configs/generated-fanout.yaml    # NATS
uv run scripts/kafka_topic_manager.py configs/generated-fanout.yaml    # Kafka/Redpanda
```
Start the sinks after provisioning:
```bash
# NATS infrastructure only
docker compose -f docker-compose-nats.yaml --profile nats-basic up -d
# NATS sink + dependencies
docker compose -f docker-compose-nats.yaml up -d substreams-nats-sink

# Kafka infrastructure (Redpanda service) only
docker compose -f docker-compose-kafka.yaml --profile kafka-basic up -d
# Kafka sink + dependencies
docker compose -f docker-compose-kafka.yaml up -d substreams-kafka-sink
```
### Monitoring
- Message queues: `nats stream list`, `docker exec redpanda-0 rpk topic list`
- Container logs: `docker logs <service> --follow`

## Testing & Quality
```bash
# Rust workspace
cargo fmt --all
cargo clippy --all-targets --all-features
cargo test --workspace

# Python suite
uv run scripts/run_tests.py            # default (unit + integration)
uv run scripts/run_tests.py --type unit
uv run scripts/run_tests.py --type integration
```
Testing standards and coverage requirements are documented in `docs/TESTING_STANDARDS.md`.

## Documentation
- `docs/REORG_HANDLING_ANALYSIS.md` & `docs/REORG_HANDLING_TECHNICAL_SPEC.md`: queue-focused reorg handling roadmap and specification
- `CLAUDE.md`: assistant workflow guidelines (applies to Codex and other LLM collaborators)

## Troubleshooting
- Missing NATS stream: `uv run scripts/nats_stream_manager.py configs/generated-fanout.yaml`
- Missing Kafka topic: `uv run scripts/kafka_topic_manager.py configs/generated-fanout.yaml`
- Schema mismatches: regenerate analysis + schema with the scripts above
- Resource limits: adjust Docker Compose profiles or backend retention settings as needed

## Contributing
1. Create a feature branch.
2. Update docs/tests alongside code.
3. Run the quality checks listed above.
4. Submit a PR describing changes and verification steps.

License information is pending; add when ready.
