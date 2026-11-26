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
