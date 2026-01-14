# Project Guidance for AI Contributors

This document explains how automated assistants (Claude, Codex, etc.) should work inside the Substreams Message Queue Sinks repository. Treat it as the authoritative workflow guide when responding to user requests.

## 1. Know the System
- Separated architecture: `substreams-core/` (shared logic), `substreams-nats-sink/`, and `substreams-kafka-sink/`. Never propose merging the sinks.
- Automation is Python-first (`scripts/*.py`). Legacy shell scripts are deprecated and should not be referenced.
- Schema analysis drives fanout configs and queue provisioning; downstream integrations live in `../substreams-sink-mq-integrations`.

Essential reading order:
1. `README.md` — project overview and operations
2. `docs/IMPLEMENTATION_LOG.md` — historical context and completed phases
3. `docs/REORG_HANDLING_ANALYSIS.md` + `docs/REORG_HANDLING_TECHNICAL_SPEC.md` — current reorg strategy
4. Component-specific code/docs as needed

## 2. Working Sessions
Before coding:
- Confirm scope with the user; avoid drive-by refactors.
- Review relevant docs/tests to ensure proposals align with production state.
- Identify whether changes touch NATS, Redpanda, or shared logic.

During implementation:
- Prefer Rust for sink logic, Python for automation/tests.
- Update or add tests first when fixing bugs or adding features.
- Keep changes focused; mention unrelated issues separately.
- Follow existing logging style (`tracing` macros, structured context).

After implementation:
- Run `cargo fmt`, `cargo clippy`, and `cargo test --workspace` for Rust changes.
- Run `uv run scripts/run_tests.py` (and narrower selections as appropriate) for Python changes.
- Document manual verification steps if automated tests are not feasible.
- Update READMEs, specs, or configuration docs when behavior changes.

## 3. Testing Expectations
- Pytest is mandatory for automation changes (`tests/` package). Maintain ≥80% coverage for new modules (see `docs/TESTING_STANDARDS.md`).
- Integration tests rely on Docker Compose (`docker-compose-*.yaml`). Use profiles (`nats-basic`, `kafka-basic`) to limit footprint.
- For Rust, ensure feature gates and async behavior are exercised (consider adding targeted unit tests under `substreams-core/tests/`).

## 4. Operational Commands
Common commands to reference in answers:
```bash
# Setup
uv venv .venv
uv pip install -r requirements.txt

# Pipeline bootstrap
uv run scripts/setup_universal_pipeline.py nats path/to/schema.proto --deploy

# Queue provisioning
uv run scripts/nats_stream_manager.py configs/generated-fanout.yaml
uv run scripts/kafka_topic_manager.py configs/generated-fanout.yaml

# Schema inspection
uv run scripts/analyze_protobuf_schema.py path/to/schema.proto configs/generated-analysis.json

# Quality
cargo fmt --all && cargo clippy --all-targets --all-features
cargo test --workspace
uv run scripts/run_tests.py

# Optional integrations now live in ../substreams-sink-mq-integrations
```

## 5. Style & Communication
- Keep responses concise, structured, and solution-oriented.
- Clarify assumptions and ask for missing information before proceeding.
- Highlight risks, missing data, or follow-up work.
- When rejecting a request, explain why (e.g., violates architecture, missing dependencies).

## 6. Reorg Handling Status
Reorg support is under active development. The sinks publish block-scoped messages, control signals, and metadata so downstream consumers can implement their own undo logic. Follow the queue-focused roadmap in `docs/REORG_HANDLING_ANALYSIS.md` / `docs/REORG_HANDLING_TECHNICAL_SPEC.md` when touching related code.

## 7. Secrets & Safety
- Never add real credentials to the repository. Sample tokens belong in `secrets/` (ignored by git).
- Avoid running commands that expose secrets in logs.
- If a secret leak is suspected, notify the user.

## 8. Conventions
- This CLAUDE.md doubles as the automation playbook for Codex and other assistants.
- For new guidelines, append sections here and reference them in pull requests or README updates.

Stay focused, keep the repo lean, and defer to the project owner for major architectural changes.
