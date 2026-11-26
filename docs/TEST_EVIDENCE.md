# Oneflow Test & Verification Evidence

> Policy: Record only directly observed commands, environments, exit codes, and outputs. Distinguish mock tests, host tests, emulator tests, and physical-device tests.

## Phase 0 — Environment, Unit Tests, APK Build & Feasibility POCs (2026-11-26)

### ENV-001: Filesystem & FUSE Probe (`downloads/op` vs `~/projects`)
- **Environment:** Host Android 16 kernel `6.12.69` `aarch64`
- **Command:** `ln -s a .../op/.probe_tmp/link` and `git init` in `.../op/.probe_tmp`
- **Observed Result:**
  - `/dev/fuse` mounted `noexec`; `chmod 755` ignored (`-rw-rw----`); `ln -s` failed (`Permission denied`); `git config` failed (`fatal: not in a git directory`).
  - `/data/user/0/ai.antigem/files/home` resides on `/dev/block/dm-99` (`f2fs`), where `git init -b main` succeeded cleanly (`~/projects/oneflow`).

### ENV-002: Android SDK & Build-Tools ARM64 Probe
- **Environment:** `adev` Ubuntu 24.04 `aarch64` container
- **Command:** `adev 'for f in /root/android-sdk/build-tools/36.0.0/*; do [ -f "$f" ] && file "$f"; done'` + tool invocations
- **Observed Result:**
  - `openjdk version "21.0.13.1"`, `javac 21.0.13.1`, `cmake 3.28.3`, `ninja 1.11.1`, NDK `28.2.13676358` (`Ubuntu clang 19.1.1`)
  - `aapt2`: `2.20-android-16.0.0_r4` (exit 0); `d8`: `D8 8.10.9-dev` (exit 0); `apksigner`: `0.9` (exit 0)
  - `aidl` & `zipalign`: `ELF 64-bit LSB pie executable, x86-64` -> fails direct execution (`cannot execute: required file not found`)

### TEST-001: Pure JVM & Android Unit Tests + Debug APK Assembly
- **Environment:** `adev` Ubuntu 24.04 `aarch64`, OpenJDK `21.0.13.1`, Gradle `9.9.2`, AGP `9.5.3`, Kotlin `2.4.21`, `compileSdk = 37`
- **Command:** `adev './gradlew :core:contracts:test :core:kernel:test :core:models:test :execution:broker:test :app:testDebugUnitTest :app:assembleDebug --console=plain'`
- **Observed Result (exit 0, `BUILD SUCCESSFUL in 2m 15s`):**
  - `ai.oneflow.core.contracts.ContractsTest`: 4 tests, 0 failures, 0 errors, 0 skipped
  - `ai.oneflow.core.kernel.MissionEngineTest`: 3 tests, 0 failures, 0 errors, 0 skipped (file-backed event/snapshot persistence + CAS + budget pause + FlowGuard)
  - `ai.oneflow.core.models.OpenAiProviderTest`: 3 tests, 0 failures, 0 errors, 0 skipped (`MockWebServer` SSE UTF-8 streaming, fragmented tool-calls, duplicate ID rejection, secret redaction)
  - `ai.oneflow.execution.broker.BrokerEngineTest`: 3 tests, 0 failures, 0 errors, 0 skipped (default-deny, approval gate, schema validation, idempotency, timeout)
  - `ai.oneflow.app.a2ui.A2UiValidatorTest`: 2 tests, 0 failures, 0 errors, 0 skipped
  - **Total:** `15` tests, `0` failures, `0` errors, `0` skipped
  - **Artifact:** `app/build/outputs/apk/debug/app-debug.apk` (`12,810,002` bytes)

### LINT-001: Android Lint (`:app:lintDebug`)
- **Command:** `adev './gradlew :app:lintDebug --console=plain'`
- **Observed Result:** Exit 0 (`BUILD SUCCESSFUL in 1m 33s`, report at `app/build/reports/lint-results-debug.html`)

---

## Phase 1 — Own Agent Kernel + BYO API (`v0.1-alpha`, 2026-11-26)

### TEST-101: Full Phase 1 JVM + Android Unit Tests, Lint, and Debug APK
- **Environment:** `adev` Ubuntu 24.04 `aarch64`, OpenJDK `21.0.13.1`, Gradle `9.9.2`, AGP `9.5.3`, Kotlin `2.4.21`
- **Command:** `adev './gradlew :core:contracts:test :core:kernel:test :core:models:test :execution:broker:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain'`
- **Observed Result (exit 0, `BUILD SUCCESSFUL in 1m 43s`):**
  - `ai.oneflow.core.contracts.ContractsTest`: 4 tests, 0 failures, 0 errors, 0 skipped
  - `ai.oneflow.core.kernel.MissionEngineTest`: 3 tests, 0 failures, 0 errors, 0 skipped
  - `ai.oneflow.core.kernel.MissionRunnerIntegrationTest`: 5 tests, 0 failures, 0 errors, 0 skipped (`MockWebServer` SSE tool loop, `WAITING_PERMISSION` approval pause/resume, `effect.intent` crash reconciliation as `UNKNOWN_OUTCOME`, context compaction + secret redaction, budget pause + coroutine cancellation)
  - `ai.oneflow.core.models.OpenAiProviderTest`: 3 tests, 0 failures, 0 errors, 0 skipped
  - `ai.oneflow.execution.broker.BrokerEngineTest`: 3 tests, 0 failures, 0 errors, 0 skipped
  - `ai.oneflow.app.a2ui.A2UiValidatorTest`: 2 tests, 0 failures, 0 errors, 0 skipped
  - `ai.oneflow.app.data.LocalFixtureServerTest`: 1 test, 0 failures, 0 errors, 0 skipped (loopback HTTP+SSE `LocalFixtureServer` end-to-end tool loop)
  - `ai.oneflow.app.data.ProviderRepositoryTest`: 1 test, 0 failures, 0 errors, 0 skipped (verifies raw API key never written to JSON store)
  - **Total:** `22` tests, `0` failures, `0` errors, `0` skipped
  - **APK Artifact:** `app/build/outputs/apk/debug/app-debug.apk` (`13,018,025` bytes)
  - **Pure-Core Check:** `grep -rn "import android\." core/ execution/` -> 0 matches.

### CI-001: GitHub Actions (`Oneflow Host & Android CI`)
- **Repository:** `https://github.com/galleryninestudio-ops/oneflow` (`PUBLIC`, branch `main`)
- **Observed Result:** Push run `4579624...` (`feat(phase0): ...`) completed green (`✓`, 2m 32s).

---

## Phase 2 — FlowCode Secure Execution (`v0.1`, 2026-11-26)

### TEST-201: Phase 2 Host, QuickJS-NG IPC, Security, NDK JNI, Lint & APK
- **Environment:** `adev` Ubuntu 24.04 `aarch64`, OpenJDK `21.0.13.1`, Gradle `9.9.2`, AGP `9.5.3`, NDK `28.2.13676358`, `qjs_worker` (Clang `19.1.1`)
- **Command:** `adev './gradlew :core:contracts:test :core:kernel:test :core:models:test :execution:broker:test :execution:flowcode:test :capabilities:files:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain'`
- **Observed Result (exit 0, `BUILD SUCCESSFUL in 2m 6s`):**
  - `25/25` unit & integration tests passing (`0` failures, `0` errors, `0` skipped), including:
    - `WorkspaceFileToolsTest` (1 test): traversal `..` denied, symlink escape denied, `fs.write` + `fs.patch` `STALE_STATE` check, `verify.file`, `TransactionJournal.undo`.
    - `FlowCodeEndToEndTest` (2 tests): responsive website gate (`discover` + batched `execute` completing in `3` model turns vs `5+` direct turns), denied-permission zero-mutation check, ambient global isolation (`require`/`std`/`os`/`fetch` undefined), path traversal denial, `try/catch` infinite-loop interrupt (`TIMEOUT`), memory cap (`2MB`), large output `ArtifactStore` truncation, and missing-worker containment.
  - Android NDK `arm64-v8a` JNI `liboneflow_qjs.so` built cleanly via CMake `3.22.1`.
  - Pure-core isolation: `grep -rn "import android\." core/ execution/ capabilities/` -> 0 matches.

### DEV-201: Physical Device Install & Cold Launch via Shizuku (`rish`)
- **Environment:** Physical Android 16 `aarch64` device via `rish` (`uid=2000(shell)`)
- **Command:** `pm install -r` + `am start -W -n ai.oneflow.app/.MainActivity`
- **Observed Result:** `Success`, `LaunchState: COLD`, `TotalTime: 932ms`, PID `12273`, clean `logcat` (no fatal/uncaught exceptions).

---

## Phase 3 — Intelligence, State & Reliability (`v0.2`, 2026-11-26)

### TEST-301: Phase 3 Core Intelligence, Kernel Revisions, Replay, Lint & APK
- **Environment:** `adev` Ubuntu 24.04 `aarch64`, OpenJDK `21.0.13.1`, Gradle `9.9.2`, AGP `9.5.3`, NDK `28.2.13676358`
- **Command:** `adev './gradlew :core:contracts:test :core:kernel:test :core:models:test :core:intelligence:test :execution:broker:test :execution:flowcode:test :capabilities:files:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain'`
- **Observed Result (exit 0, `BUILD SUCCESSFUL in 2m 39s`):**
  - `30/30` tests passing (`0` failures, `0` errors, `0` skipped), including `Phase3IntelligenceTest` (5 tests: DAG cycle rejection + blocked propagation, scoped memory cross-workspace isolation + supersession + `FlowWorld` TTL expiration, untrusted repo skill/hook rejection + fail-closed security hooks, read-only `FlowReplayEngine` zero side effects + grant-cleared `forkMission` + `REVISION_CONFLICT` on stale `requirementsRevision`, deterministic `ContextIndex` + `FlowMindPlanner`).
  - `:app:lintDebug` passed; `:app:assembleDebug` -> `app-debug.apk` (`14,533,070` bytes).
  - Pure-core isolation: `grep -rn "import android\." core/ execution/ capabilities/` -> 0 matches.

---

## Phase 4 — Native Development Environment (`v0.3`, 2026-11-26)

### TEST-401: FlowRuntime, FlowPkg ECDSA Verification/Rollback, Python Fixture Build/Test, Lint & APK
- **Environment:** `adev` Ubuntu 24.04 `aarch64`, OpenJDK `21.0.13.1`, Gradle `9.9.2`, AGP `9.5.3`, Python `3.12.3`
- **Commands:**
  - `adev './gradlew :capabilities:development:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain'`
- **Observed Result (exit 0, `BUILD SUCCESSFUL in 2m 8s`):**
  - `32/32` tests passing (`0` failures, `0` errors, `0` skipped across 12 suites), including `Phase4DevelopmentTest` (2 tests: ECDSA P-256 + SHA-256 package verification, tampered payload rejection, v2 -> v1 rollback; Python fixture detection via `env.detect`, symbol extraction via `code.symbols`, approved `python3 test_calc.py` execution -> exit 0, unauthorized `sh` shell denial `DENIED`, and intentional failure -> non-zero exit code).
  - `:app:lintDebug` passed (0 errors); `:app:assembleDebug` -> `app-debug.apk` (`14,547,480` bytes).

### ARCH-001: Pure Core Boundary Check
- **Command:** `grep -rn "import android\." core/ execution/`
- **Observed Result:** Zero matches (`PASS: zero android.* imports in core/ and execution/`).

### POC-001: QuickJS-NG `v0.18.0` Host C & Android NDK ARM64 JNI
- **Environment:** Ubuntu Clang `19.1.1` + NDK `28.2.13676358` (`aarch64-linux-android26`)
- **Pinned Source:** `quickjs-ng/quickjs` tag `v0.18.0` (`d7215b0ad3fc2c562e8317fd9bca058fd4863804`, MIT)
- **Observed Result (exit 0):**
  - Host `./build/qjs_poc`:
    - `[async_host_call] rc=0 -> OK: {"parsed":{"tool":"fs.stat","echo":{"path":"README.md"}},"note":"await ok"}`
    - `[error_propagation] rc=1 -> REJECTED(interrupted=0): mock capability denied`
    - `[memory_limit] rc=-1 -> COMPILE_OR_SYNC_ERR(interrupted=0): InternalError: out of memory`
    - `[infinite_loop_interrupt] rc=2 -> COMPILE_OR_SYNC_ERR(interrupted=1): InternalError: interrupted` (uncatchable even inside `try/catch`)
    - `[no_ambient_node_std] rc=0 -> OK: {"hasRequire":"undefined","hasStd":"undefined","hasOs":"undefined"}`
    - `ALL QUICKJS-NG PHASE 0 HOST CHECKS PASSED`
  - NDK JNI `build-android/liboneflow_qjs_jni.so`: `ELF 64-bit LSB shared object, ARM aarch64`, dynamically linked against `liblog.so`, `libm.so`, `libdl.so`, `libc.so`.

### POC-002: Isolated Worker & Native Execution Probes
- **Observed Result (exit 0):**
  - `worker_poc`: `worker_output(22): uid=0 env_home=(null)` (inside proot), `watchdog_sigkill_terminated=1 signal=9`.
  - `abi_probe_bionic`: `ELF 64-bit LSB pie executable, ARM aarch64, interpreter /system/bin/linker64`; executes on `f2fs` (`sys=Linux machine=aarch64 uid=10448`) and returns `Permission denied` when copied to `/storage/emulated/0/Download/op` (`/dev/fuse`, `noexec`).

### POC-003: REA (`rea-agents@6.10.1`) Local Probe
- **Command:** `adev 'npx --yes rea-agents@6.10.1 --help'` / `doctor` / `capabilities`
- **Observed Result (exit 0):** `204` CLI commands, `263` MCP tools on Node `24.22.3`; `rea-artifact-graph` available locally; `native-macos`/`hopper` `unsupported_host`; `ghidra`/`jadx`/`ida`/`kuna` report missing external engine env vars cleanly.
