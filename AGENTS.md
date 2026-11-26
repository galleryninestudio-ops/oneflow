# AGENTS.md — Oneflow Engineering Navigation & Rules

**Primary Source of Truth:** [docs/ONEFLOW_MASTER_BUILD_SPEC.md](docs/ONEFLOW_MASTER_BUILD_SPEC.md)

## Quick Navigation
- `docs/ONEFLOW_MASTER_BUILD_SPEC.md` — Master engineering specification & phase gates.
- `docs/IMPLEMENTATION_STATUS.md` — Current milestone, checklist (`NOT_STARTED | IN_PROGRESS | BLOCKED | VERIFIED`), and §18.1 status reports.
- `docs/DECISIONS.md` — Architecture Decision Records (ADRs).
- `docs/COMPATIBILITY.md` — Verified Android/ARM64/toolchain/dependency feasibility matrix.
- `docs/THREAT_MODEL.md` — Trust boundaries, assets, attack surfaces, and security invariants.
- `docs/DEPENDENCIES.md` — Pinned dependencies, upstream commits/tags, licenses, and fallbacks.
- `docs/TEST_EVIDENCE.md` — Reproducible test commands, environment metadata, and observed outputs.
- `core/contracts/` — Pure JVM contracts, IDs, errors, state schemas, JSON Schema validation, ports (zero Android imports).
- `core/kernel/` — Pure JVM mission state machine, CAS revisions, budget accounting, FlowGuard, durable event store.
- `core/models/` — Pure JVM external OpenAI-compatible API gateway, SSE parser, capability profiles.
- `execution/broker/` — Pure JVM tool registry, default-deny FlowPolicy evaluator, typed broker, receipts.
- `app/` — Android Compose UI (`ai.oneflow.app`), native JNI / isolated worker POCs, platform adapters.
- `experiments/` — Isolated Phase 0 feasibility POCs (not wired into production until verified).
- `fixtures/` — Deterministic synthetic test fixtures (SSE payloads, tool manifests, schemas).

## Non-Negotiable Rules
1. **No local AI models** (no bundled weights, llama.cpp, LiteRT-LM, Gemini Nano, local embeddings, or on-device inference).
2. **No marketing features** (no ads, SEO/ASO, CRM, lead generation, or social outreach).
3. **No mandatory external agent CLIs** (no Antigravity, Claude Code, OpenCode, Codex, Pi, Termux, PRoot, or root required by Oneflow).
4. **Pure core boundary**: `core/*` and `execution/*` depend only on core contracts and JVM libraries — never import `android.*` or UI frameworks.
5. **Default deny & honest states**: Policy checks happen at the host broker boundary. Missing/unproven capabilities stay disabled and clearly labeled. Never fabricate test passes, APIs, or progress.
6. **Verify before claiming**: Every claim in `IMPLEMENTATION_STATUS.md` and `TEST_EVIDENCE.md` must cite exact commands, environment, and observed outputs.
7. **Incremental progress**: Complete each phase gate before advancing. Keep storage migrations and tool schemas backward-compatible.
