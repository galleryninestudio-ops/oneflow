# Oneflow

**Your models. Your tools. Your agent.**

Oneflow is an independent, agent-native Android workstation with bring-your-own external model APIs, Code Mode tool orchestration (`discover` / `execute`), durable mission state, typed capability brokerage, and native Jetpack Compose UI.

See [docs/ONEFLOW_MASTER_BUILD_SPEC.md](docs/ONEFLOW_MASTER_BUILD_SPEC.md) for the complete specification and [docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md) for current build and verification status.

## Repository Layout
- `core/contracts` — Pure Kotlin/JVM domain types, schemas, errors, ports
- `core/kernel` — Mission state machine, budgets, FlowGuard, durable event store
- `core/models` — OpenAI-compatible Chat Completions gateway & streaming SSE parser
- `execution/broker` — Tool registry, policy engine (default deny), typed tool broker
- `app` — Android application (`ai.oneflow.app`) using Jetpack Compose
- `experiments` — Isolated Phase 0 feasibility POCs (QuickJS-NG, isolated worker, native exec, Android SDK, A2UI/REA/WebView/Shizuku probes)
- `fixtures` — Synthetic test fixtures
- `docs` — Engineering records, ADRs, threat model, dependency inventory, test evidence

## Build & Test (Termux + `adev` ARM64 environment)
Host tests and debug APK assembly run inside the `adev` Android build container (`OpenJDK 21`, `Android SDK 36/37`, `ARM64 aapt2`, `ARM64-rebuilt NDK 28.2.13676358`):

```bash
adev ./gradlew test --console=plain
adev ./gradlew :app:assembleDebug --console=plain
```

> **Note:** On Android shared storage (`/sdcard` / `/storage/emulated`), FUSE enforces `noexec` and blocks symlinks/`chmod`. Always keep and build this repository on a POSIX Linux filesystem (`~/projects/oneflow`).
