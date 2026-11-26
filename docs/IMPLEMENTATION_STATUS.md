# Oneflow Implementation Status

## Current Milestone Summary (§18.1 Format)
- **Phase / milestone:** Phase 0 — Feasibility and foundation (v0.0.1) / Ticket `OF-0001`
- **State:** `VERIFIED` (host JVM, native ARM64 POCs, and debug APK build; physical-device `adb` runtime probes `DEFERRED` awaiting user `adb` approval)
- **Git commit / branch:** `main`
- **Implemented real capabilities:**
  - Multi-module Kotlin/Gradle project (`:core:contracts`, `:core:kernel`, `:core:models`, `:execution:broker`, `:app`) with verified pure-core isolation (`0` `android.*` imports in `core/` or `execution/`).
  - Pure JVM domain contracts, JSON Schema subset validator, secret redactor, CAS-revisioned mission state machine, file-backed append-only event store + atomic snapshots, budget exhaustion pause, FlowGuard 3-failure trip, deterministic verification gate.
  - OpenAI-compatible Chat Completions SSE parser (UTF-8 chunking, fragmented `tool_calls` assembly, duplicate/conflicting ID rejection, usage reporting vs null, key redaction) + OkHttp provider tested via `MockWebServer`.
  - Default-deny `PolicyEngine`, `DefaultToolRegistry`, `SafeToolBroker` with schema validation, idempotency caching, timeout enforcement, and execution receipts.
  - Minimal AMOLED Jetpack Compose `:app` (`ai.oneflow.app`) showing honest BYO API status, live `/models` probe, real Android capability matrix, `IsolatedWorkerProbeService` (`android:isolatedProcess="true"` + Binder `Parcel`), and validated A2UI v0.9/v1.0 Compose subset renderer.
  - Phase 0 `experiments/`: QuickJS-NG `v0.18.0` host C + NDK `arm64-v8a` JNI library, Linux isolated worker POC, Bionic ABI / FUSE `noexec` probe, `rea-agents@6.10.1` capability audit, Android SDK build-tools audit.
- **Acceptance criteria passed / failed / not run:**
  - Passed: `15/15` JUnit 5 tests, `:app:assembleDebug` (`12,810,002` bytes), pure-core import check, `qjs_poc` (5/5 checks), `liboneflow_qjs_jni.so` NDK link, `worker_poc`, `abi_probe_bionic`, `rea` CLI/doctor probe.
  - Deferred (not run): On-device `adb` install/launch & Android runtime checks (`isolatedProcess` UID, Shizuku binder, Accessibility, MediaProjection) awaiting user `adb` approval.
- **Commands and actual test results:** Recorded in `docs/TEST_EVIDENCE.md`.
- **Environment (host, Android SDK/target, ABI, device, provider):** Linux `6.12.69` `aarch64` Android; Termux + `adev` Ubuntu 24.04; OpenJDK `21.0.13.1`; Gradle `9.9.2`; AGP `9.5.3`; Kotlin `2.4.21`; `compileSdk = 37` (`android-37.0`); `targetSdk = 37`; `minSdk = 26`; NDK `28.2.13676358` (ARM64 LLVM `19.1.1`).
- **Evidence paths / artifacts / screenshots:** `docs/TEST_EVIDENCE.md`, `docs/COMPATIBILITY.md`, `docs/DECISIONS.md`, `app/build/outputs/apk/debug/app-debug.apk`.
- **Upstream sources, pinned versions, licenses:** Recorded in `docs/DEPENDENCIES.md`.
- **Security and privacy checks:** `docs/THREAT_MODEL.md` baseline; `SecretRedactor` verified in `ContractsTest` and `OpenAiProviderTest`; `allowBackup="false"`; all Phase 0 experimental features (`flowcode.quickjs`, `agentShell`, `shizuku`, `accessibility`, `capture`, `webMcp`, `rea`) gated `false`.
- **Known blockers, limitations, assumptions:** `/storage/emulated/0` FUSE blocks symlinks/`chmod`/`exec`/`git init`; `build-tools/36.0.0/{aidl,zipalign}` are x86-64 ELFs on ARM64 host; physical device tests awaiting explicit `adb` consent.
- **Deviations from this spec and ADR link:** Repo placed in `~/projects/oneflow` (`ADR-0001`), Binder `Parcel` replaces `aidl` (`ADR-0004`, `ADR-0005`), `composeBom = 2026.09.00` pinned for `android-37.0` (`ADR-0002`).
- **Next smallest vertical slice / ticket:** `OF-0002` — Phase 1: Durable mission loop + Android Keystore FlowVault + Compose onboarding/provider/chat streaming & fixture broker end-to-end.

---

## Phase Checklist
- **Phase 0 — Feasibility and foundation (v0.0.1):** `VERIFIED` (host/build/POC scope; device runtime probes `DEFERRED`)
  - [x] Inspect environment, git, SDK/NDK/JDK, upstream dependencies & licenses
  - [x] Create `AGENTS.md`, `docs/ONEFLOW_MASTER_BUILD_SPEC.md`, `DECISIONS.md`, `COMPATIBILITY.md`, `THREAT_MODEL.md`, `DEPENDENCIES.md`
  - [x] Initialize Gradle/Kotlin multi-module project (`:core:contracts`, `:core:kernel`, `:core:models`, `:execution:broker`, `:app`) + GitHub Actions CI
  - [x] JVM & unit tests (`15/15` passing): mission state transitions, tool schema validation, OpenAI SSE parser, broker policy/idempotency, A2UI subset validator
  - [x] Minimal Compose debug APK (`ai.oneflow.app`) exposing honest connection & Phase 0 feature status
  - [x] QuickJS-NG (`v0.18.0`) host C + Android NDK JNI POC (`await`, host call, memory limit, interrupt)
  - [x] Isolated worker POC (host process/env isolation + Android `isolatedProcess` service build)
  - [x] Native execution / target SDK POC (Bionic ELF & FUSE `noexec` boundary check)
  - [x] Android SDK on-device POC & compatibility assessment
  - [x] Shizuku / Accessibility / MediaProjection / WebView compile probes + REA & A2UI Phase 0 probes
- **Phase 1 — Own agent kernel + BYO API (v0.1-alpha):** `IN_PROGRESS` (starting next)
- **Phase 2 — FlowCode secure execution (v0.1):** `NOT_STARTED`
- **Phase 3 — Intelligence, state and reliability (v0.2):** `NOT_STARTED`
- **Phase 4 — Native development environment (v0.3):** `NOT_STARTED`
- **Phase 5 — FlowWeb, FlowBrowser, research (v0.4):** `NOT_STARTED`
- **Phase 6 — Android computer use (v0.5):** `NOT_STARTED`
- **Phase 7 — REA, FlowForge, FlowTeach (v0.6):** `NOT_STARTED`
- **Phase 8 — Teams, workflows, learning and remote (v0.7):** `NOT_STARTED`
- **Phase 9 — Complete polished workspace (v0.8):** `NOT_STARTED`
- **Phase 10 — Production engineering and release (v0.9 → v1.0):** `NOT_STARTED`
