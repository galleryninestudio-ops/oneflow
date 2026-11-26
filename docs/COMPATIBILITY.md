# Oneflow Compatibility & Feasibility Matrix

**Last updated:** 2026-11-26 (Phase 0 environment inspection in progress)

## 1. Host & Build Environment Observed
| Component | Observed Environment | Status | Notes |
|---|---|---|---|
| Host OS / Kernel | Linux `6.12.69-android16-6-...` `aarch64` Android | VERIFIED | On-device ARM64 Termux + Ubuntu 24.04 proot (`adev`) |
| JDK | OpenJDK `21.0.13.1` (`java-21-openjdk-arm64`) | VERIFIED | Inside `adev` container |
| Android SDK | `platforms/android-36`, `android-37.0`, `build-tools/36.0.0`, `cmake/3.22.1` | VERIFIED | Inside `adev` container (`/root/android-sdk`) |
| Android NDK | `28.2.13676358` rebuilt with Ubuntu ARM64 LLVM `19.1.1` + `lld` | VERIFIED | `clang` / `clang++` ARM64 wrappers verified |
| CMake / Ninja | `cmake 3.28.3`, `ninja 1.11.1` (ARM64) | VERIFIED | Inside `adev` container |
| `aapt2` | Termux ARM64 `2.20-android-16.0.0_r4` | VERIFIED | Overrides x86-64 maven `aapt2` via `adev` `gradle.properties` |
| `d8` / `apksigner` | `D8 8.10.9-dev`, `apksigner 0.9` (Java) | VERIFIED | Run natively on ARM64 JDK 21 |
| `aidl` / `zipalign` | x86-64 ELF in `build-tools/36.0.0` | **UNSUPPORTED_DIRECT** | Fails direct execution (`required file not found`). Avoid `buildFeatures.aidl`; AGP uses Java `zipflinger` |
| Shared Storage (`/storage/emulated/0`) | `/dev/fuse` (`noexec`, no symlinks, no `chmod`) | **BLOCKED_FOR_REPO** | `git init` failed (`fatal: not in a git directory`). Repo hosted on `f2fs` (`~/projects/oneflow`) |
| Physical Device Runtime (`adb`) | `adb` present (`37.0.0p1-3`), awaiting explicit user consent | **DEFERRED** | User requested host tests + `assembleDebug` first; ask before `adb install` |

## 2. Phase 0 Feasibility Experiments (Updated as Experiments Run)
| Experiment | Scope | Status | Blocker / Finding |
|---|---|---|---|
| Host Kotlin/Gradle/JUnit build | `:core:*` + `:execution:broker` JVM tests | NOT_STARTED | Pending build creation |
| Compose Debug APK build | `:app:assembleDebug` on ARM64 `adev` | NOT_STARTED | Pending `:app` creation |
| QuickJS-NG Host C POC | `v0.18.0` async/Promise, host call, mem limit, interrupt | NOT_STARTED | Pending `experiments/quickjs-poc` |
| QuickJS-NG Android NDK JNI | `liboneflow_qjs_poc.so` compiled for `arm64-v8a` | NOT_STARTED | Pending NDK CMake build |
| Isolated Worker POC | Host process/env/FD separation + Android `isolatedProcess` service build | NOT_STARTED | Runtime Android UID check requires device run |
| Native Execution & Target SDK POC | Bionic/static ARM64 execution scopes & Android 10+ `noexec` constraints | NOT_STARTED | Pending `experiments/native-exec-poc` |
| Android SDK On-Device POC | Feasibility of ARM64 OpenJDK/AAPT2/D8/apksigner inside app sandbox | NOT_STARTED | Inspecting constraints |
| Shizuku / Accessibility / MediaProjection / WebView | Compile-time API probes & feature-flagged capability inspection | NOT_STARTED | Runtime verification awaits user `adb` approval |
| REA & A2UI Inspection | `rea-agents@6.10.1` local inspect + A2UI v0.9/v1.0 schema/Compose subset | NOT_STARTED | Pending `experiments/` probe |

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
