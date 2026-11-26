# Oneflow Implementation Status

## Current Milestone Summary (§18.1 Format)
- **Phase / milestone:** Phase 1 — Own agent kernel + BYO API (v0.1-alpha)
- **State:** `VERIFIED` (host JVM, loopback fixture HTTP/SSE integration, `lintDebug`, and `assembleDebug`; real live external LLM key & physical-device `adb` UI runtime remain `DEFERRED` awaiting user key / `adb` consent)
- **Git commit / branch:** `main` (`origin`: `https://github.com/galleryninestudio-ops/oneflow`)
- **Implemented real capabilities:**
  - `InMemoryLeaseManager` with monotonic fencing tokens and TTL renewal/release.
  - `FlowContextCompiler` with layered trust boundaries, immutable objective/requirements preservation, deterministic token-budget compaction, and secret redaction.
  - `MissionRunner` durable control loop: streaming SSE consumption, `effect.intent` -> broker dispatch -> `effect.observed`, crash recovery (`effect.reconciled_unknown` without blind re-execution), native `WAITING_PERMISSION` approval pause/approve/deny/resume, `PAUSED_BUDGET`, `CANCELLED`, and `Verifier` completion gate.
  - `Phase1FixtureTools` (`fixture_echo`, `fixture_math_add`, approval-gated `fixture_publish_note`) behind `SafeToolBroker` + `DefaultDenyPolicyEngine`.
  - `AndroidKeystoreVault` (`AES/GCM/NoPadding` 256-bit in `noBackupFilesDir`), `ProviderRepository` (stores `keyAlias` only, never raw keys on disk), `ProviderPresets`, labeled loopback `LocalFixtureServer`, `OneflowSessionController`, and 3-tab AMOLED Compose UI (`Chat`, `Missions`, `Setup`).
- **Acceptance criteria passed / failed / not run:**
  - Passed: `22/22` JUnit 5 tests (`MissionRunnerIntegrationTest`, `LocalFixtureServerTest`, `ProviderRepositoryTest`, `OpenAiProviderTest`, `BrokerEngineTest`, `MissionEngineTest`, `ContractsTest`, `A2UiValidatorTest`), `:app:lintDebug`, `:app:assembleDebug` (`13,018,025` bytes), pure-core `android.*` isolation check, GitHub Actions Phase 0 CI green (`4579624`).
  - Not run / deferred: Live external provider API test (no external API key supplied in session) and on-device Android runtime launch via `adb` (awaiting explicit user `adb` approval).
- **Commands and actual test results:** Recorded in `docs/TEST_EVIDENCE.md`.
- **Environment (host, Android SDK/target, ABI, device, provider):** Linux `6.12.69` `aarch64` Android; Termux + `adev` Ubuntu 24.04; OpenJDK `21.0.13.1`; Gradle `9.9.2`; AGP `9.5.3`; Kotlin `2.4.21`; `compileSdk = 37` (`android-37.0`); `targetSdk = 37`; `minSdk = 26`; `MockWebServer 5.5.1` + loopback `LocalFixtureServer`.
- **Evidence paths / artifacts / screenshots:** `docs/TEST_EVIDENCE.md`, `app/build/outputs/apk/debug/app-debug.apk`, `app/build/reports/lint-results-debug.html`.
- **Upstream sources, pinned versions, licenses:** Recorded in `docs/DEPENDENCIES.md`.
- **Security and privacy checks:** `SecretRedactor` verified in `MissionRunnerIntegrationTest`, `OpenAiProviderTest`, `ContractsTest`; `ProviderRepositoryTest` verifies raw key never hits disk; `allowBackup="false"`; Phase 0 feature flags (`flowcode.quickjs`, `agentShell`, `shizuku`, etc.) remain `false`.
- **Known blockers, limitations, assumptions:** Real external provider test requires a user-supplied key; on-device Android Keystore hardware test requires `adb` consent.
- **Deviations from this spec and ADR link:** `ADR-0001`..`ADR-0006` in `docs/DECISIONS.md`.
- **Next smallest vertical slice / ticket:** `OF-0003` — Phase 2: FlowCode QuickJS-NG isolated worker integration (`discover` / `execute`), path-contained workspace FS tools (`fs.list/glob/read/write/patch/grep`), content-addressed artifact store, and sandbox security suite.

---

## Phase Checklist
- **Phase 0 — Feasibility and foundation (v0.0.1):** `VERIFIED` (host/build/POC scope; device runtime probes `DEFERRED`)
  - [x] Repo, `AGENTS.md`, ADRs, threat model, dependencies, compatibility matrix, public GitHub repo + green CI
  - [x] Multi-module Gradle/Kotlin project + unit tests + Compose debug APK
  - [x] QuickJS-NG (`v0.18.0`) host C + Android NDK JNI POC, isolated worker POC, Bionic ABI probe, REA & A2UI probes
- **Phase 1 — Own agent kernel + BYO API (v0.1-alpha):** `VERIFIED` (host/mock/loopback/APK scope; live external key & `adb` device run `DEFERRED`)
  - [x] Leases, context compiler, durable `MissionRunner`, `effect.intent`/`effect.observed` reconciliation, approval pause/resume, budget pause, cancellation
  - [x] OpenAI-compatible SSE streaming, `MockWebServer` & loopback `LocalFixtureServer` tests, `AndroidKeystoreVault` + `ProviderRepository`
  - [x] Minimal 3-tab AMOLED Compose UI (`Chat`, `Missions`, `Setup`) with streaming, tool cards, native approvals, and persisted mission inspection
- **Phase 2 — FlowCode secure execution (v0.1):** `IN_PROGRESS` (next)
- **Phase 3 — Intelligence, state and reliability (v0.2):** `NOT_STARTED`
- **Phase 4 — Native development environment (v0.3):** `NOT_STARTED`
- **Phase 5 — FlowWeb, FlowBrowser, research (v0.4):** `NOT_STARTED`
- **Phase 6 — Android computer use (v0.5):** `NOT_STARTED`
- **Phase 7 — REA, FlowForge, FlowTeach (v0.6):** `NOT_STARTED`
- **Phase 8 — Teams, workflows, learning and remote (v0.7):** `NOT_STARTED`
- **Phase 9 — Complete polished workspace (v0.8):** `NOT_STARTED`
- **Phase 10 — Production engineering and release (v0.9 → v1.0):** `NOT_STARTED`
