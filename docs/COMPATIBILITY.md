# Oneflow Compatibility & Feasibility Matrix

**Last updated:** 2026-11-26 (Phases 0–10 verified on host, `adev` ARM64, and physical Android 16 ARM64 device via Shizuku `rish`)

## 1. Host & Build Environment Observed
| Component | Observed Environment | Status | Notes |
|---|---|---|---|
| Host OS / Kernel | Linux `6.12.69-android16-6-...` `aarch64` Android | VERIFIED | On-device ARM64 Termux + Ubuntu 24.04 proot (`adev`) |
| JDK | OpenJDK `21.0.13.1` (`java-21-openjdk-arm64`) | VERIFIED | Inside `adev` container |
| Android SDK | `platforms/android-36`, `android-37.0`, `build-tools/36.0.0`, `cmake/3.22.1` | VERIFIED | `compileSdk = 37` requires `composeBom = 2026.09.00` (`2026.11.01` requires `37.1`) |
| Android NDK | `28.2.13676358` rebuilt with Ubuntu ARM64 LLVM `19.1.1` + `lld` | VERIFIED | `arm64-v8a` JNI `.so` compiles natively |
| CMake / Ninja | `cmake 3.28.3`, `ninja 1.11.1` (ARM64) | VERIFIED | Inside `adev` container |
| `aapt2` | Termux ARM64 `2.20-android-16.0.0_r4` | VERIFIED | Overrides x86-64 maven `aapt2` via `adev` `gradle.properties` |
| `d8` / `apksigner` | `D8 8.10.9-dev`, `apksigner 0.9` (Java) | VERIFIED | Run natively on ARM64 JDK 21 |
| `aidl` / `zipalign` | x86-64 ELF in `build-tools/36.0.0` | **UNSUPPORTED_DIRECT** | Fails direct execution (`required file not found`). Binder `Parcel` avoids `aidl`; AGP `zipflinger` packs APKs |
| Shared Storage (`/storage/emulated/0`) | `/dev/fuse` (`noexec`, no symlinks, no `chmod`) | **BLOCKED_FOR_REPO** | `git init` failed; binary execution returns `Permission denied`. Repo hosted on `f2fs` (`~/projects/oneflow`) |
| Physical Device Runtime (`rish` / `adb`) | Shizuku `uid=2000(shell)` on Android 16 ARM64 | **VERIFIED** | Installed (`pm install -r -S`) and cold-launched `ai.oneflow.app/.MainActivity` across Phases 1–9 |

## 2. Phase 0 Feasibility Experiments
| Experiment | Scope | Status | Blocker / Finding |
|---|---|---|---|
| Host Kotlin/Gradle/JUnit build | `:core:*` + `:execution:broker` + `:app:testDebugUnitTest` | **VERIFIED** | `15/15` JUnit 5 tests passed (Gradle `9.9.2`, Kotlin `2.4.21`) |
| Compose Debug APK build | `:app:assembleDebug` on ARM64 `adev` | **VERIFIED** | `app-debug.apk` (`12,810,002` bytes) built with AGP `9.5.3` |
| QuickJS-NG Host C POC | `v0.18.0` async/Promise, host call, mem limit, interrupt | **VERIFIED** | All 5 assertions passed; `InternalError: interrupted` is uncatchable even inside `try/catch` |
| QuickJS-NG Android NDK JNI | `liboneflow_qjs_jni.so` compiled for `arm64-v8a` | **VERIFIED** | Links `liblog.so`, `libm.so`, `libdl.so`, `libc.so` (note: do not pass `-lpthread` on Android Bionic) |
| Isolated Worker POC | Host process/env/FD/RLIMIT + Android `isolatedProcess` service compile | **PARTIAL_HOST_VERIFIED** | Host `clearenv` + `SIGKILL` watchdog verified; same-UID Linux subprocess is not a file sandbox. Android `isolatedProcess` UID separation requires device run |
| Native Execution & Target SDK POC | Bionic `aarch64-linux-android26-clang` ELF + `f2fs` vs `/dev/fuse` | **VERIFIED** | Runs on `f2fs`; `Permission denied` on FUSE `noexec`; modern target SDK W^X requires packaged native libs or approved runtime |
| Android SDK On-Device POC | Assessment of ARM64 OpenJDK/AAPT2/D8/apksigner + `lzhiyong/android-sdk-tools` | **ASSESSED** | Static `android-sdk-tools-static-aarch64.zip` (`35.0.2`) identified; in-app execution gated until Phase 4 |
| Shizuku / Accessibility / MediaProjection / WebView | Compile-time API probes (`androidx.webkit:1.18.0`) & `CapabilityProbe` | **COMPILE_VERIFIED** | Runtime device checks deferred until user authorizes `adb` testing |
| REA & A2UI Inspection | `rea-agents@6.10.1` local inspect + A2UI v0.9/v1.0 schema/Compose subset | **VERIFIED** | REA runs on Node 24 (`rea-artifact-graph` local; Ghidra/JADX/IDA require external engines; macOS/Hopper unsupported). Validated A2UI Compose subset implemented |

## 3. Feature Flags (Phase 0 Default Posture)
| Feature Flag | Default | Reason |
|---|---|---|
| `flowcode.quickjs.enabled` | `false` | Phase 0 POC only; enabled only in Phase 2 after sandbox & broker gates |
| `runtime.agent_shell.enabled` | `false` | Arbitrary agent shell prohibited until OS-level isolation proven (Phase 4) |
| `android.shizuku.enabled` | `false` | Optional capability; disabled until user connects Shizuku & approves (Phase 6) |
| `android.accessibility.enabled` | `false` | Optional OS Settings opt-in (Phase 6) |
| `android.capture.enabled` | `false` | Optional MediaProjection session consent (Phase 6) |
| `browser.webmcp.enabled` | `false` | Experimental; feature-check required (Phase 5) |
| `builder.rea.enabled` | `false` | Pending REA backend compatibility validation (Phase 7) |
