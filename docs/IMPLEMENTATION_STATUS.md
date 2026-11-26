# Oneflow Implementation Status

## Current Milestone Summary (§18.1 Format)
- **Phase / milestone:** Phase 2 — FlowCode secure execution (v0.1)
- **State:** `VERIFIED` (host JVM, real QuickJS-NG `qjs_worker` IPC, NDK `arm64-v8a` JNI APK, `lintDebug`, and physical device Shizuku install & cold launch)
- **Git commit / branch:** `main` (`origin`: `https://github.com/galleryninestudio-ops/oneflow`)
- **Implemented real capabilities:**
  - `:capabilities:files`: `WorkspacePathGuard` (realpath & symlink escape protection), `ArtifactStore` (SHA-256 content-addressed storage), `TransactionJournal` (atomic local write undo), and `WorkspaceFileTools` (`fs.list`, `fs.glob`, `fs.read`, `fs.grep`, `fs.write`, `fs.patch`, `artifact.create`, `artifact.read`, `verify.file`).
  - `:execution:flowcode` + `native/qjs-worker`: real QuickJS-NG `v0.18.0` isolated worker process (`qjs_worker`), `FlowSdkGenerator`, `QuickJsProcessScriptEngine`, `FlowCodeTools` (`discover`, `execute`, `mission.status`).
  - `:app`: NDK `arm64-v8a` JNI `liboneflow_qjs.so` inside `FlowCodeIsolatedWorkerService` (`android:isolatedProcess="true"`), scoped workspace permission toggles, and `RestrictedWorkspacePreviewCard` (`https://oneflow-preview.invalid/`, JS disabled).
- **Acceptance criteria passed / failed / not run:**
  - Passed: `25/25` tests, `:app:lintDebug`, `:app:assembleDebug` + NDK CMake, pure-core import check, and `rish` Shizuku install/launch (`932ms`, zero crashes).
  - Baseline comparison recorded: batched FlowCode (`discover` + 1 `execute` + summary) completed the responsive website task in `3` model turns vs `5+` sequential direct tool turns.
- **Commands and actual test results:** Recorded in `docs/TEST_EVIDENCE.md`.
- **Environment (host, Android SDK/target, ABI, device, provider):** Linux `6.12.69` `aarch64` Android 16; `adev` Ubuntu 24.04; OpenJDK `21.0.13.1`; Gradle `9.9.2`; AGP `9.5.3`; NDK `28.2.13676358`; physical device `rish` (`uid=2000`).
- **Evidence paths / artifacts / screenshots:** `docs/TEST_EVIDENCE.md`, `app/build/outputs/apk/debug/app-debug.apk`.
- **Upstream sources, pinned versions, licenses:** Recorded in `docs/DEPENDENCIES.md`.
- **Security and privacy checks:** Traversal, symlink escape, ambient globals (`require`/`std`/`os`/`fetch`), infinite-loop interrupt, heap cap, output truncation, denied-permission zero mutation, and restricted WebView origin verified.
- **Known blockers, limitations, assumptions:** Live external LLM API test still requires a user-supplied key (loopback fixture & `MockWebServer` verified).
- **Deviations from this spec and ADR link:** `ADR-0001`..`ADR-0006` in `docs/DECISIONS.md`.
- **Next smallest vertical slice / ticket:** `OF-0004` — Phase 3: `:core:intelligence` (`TaskDagScheduler`, `FlowMind`, `FlowMemory`, `FlowWorld`, `ContextIndex`, `FlowSkills`, `FlowHooks`, `FlowReplay`, `MissionReceipt`) + UI trace/replay/undo.

---

## Phase Checklist
- **Phase 0 — Feasibility and foundation (v0.0.1):** `VERIFIED`
- **Phase 1 — Own agent kernel + BYO API (v0.1-alpha):** `VERIFIED`
- **Phase 2 — FlowCode secure execution (v0.1):** `VERIFIED`
- **Phase 3 — Intelligence, state and reliability (v0.2):** `IN_PROGRESS` (starting now)
- **Phase 4 — Native development environment (v0.3):** `NOT_STARTED`
- **Phase 5 — FlowWeb, FlowBrowser, research (v0.4):** `NOT_STARTED`
- **Phase 6 — Android computer use (v0.5):** `NOT_STARTED`
- **Phase 7 — REA, FlowForge, FlowTeach (v0.6):** `NOT_STARTED`
- **Phase 8 — Teams, workflows, learning and remote (v0.7):** `NOT_STARTED`
- **Phase 9 — Complete polished workspace (v0.8):** `NOT_STARTED`
- **Phase 10 — Production engineering and release (v0.9 → v1.0):** `NOT_STARTED`
