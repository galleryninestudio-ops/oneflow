# Oneflow Architecture Decision Records (ADRs)

## ADR-0001 — Repository Location & Build Host Environment
- **Date:** 2026-11-26
- **Status:** Accepted
- **Context:** The initial conversation workspace (`/storage/emulated/0/Download/op`) resides on Android FUSE shared storage (`/dev/fuse`), which mounts `noexec`, ignores `chmod +x`, fails `ln -s` (`Permission denied`), and failed `git init` (`fatal: not in a git directory` due to FUSE ownership/permissions). Meanwhile, the on-device `adev` Ubuntu 24.04 ARM64 build container provides OpenJDK 21.0.13.1, Android SDK platforms `android-36` & `android-37.0`, `build-tools/36.0.0` (with Termux ARM64 `aapt2`), CMake 3.28.3, Ninja 1.11.1, and NDK `28.2.13676358` rebuilt with Ubuntu ARM64 LLVM 19.
- **Decision:** Host the Oneflow Git/Gradle repository at `/data/user/0/ai.antigem/files/home/projects/oneflow` (`f2fs`, full POSIX semantics), leaving `downloads/op/Main.kt` untouched. Export deliverables to `downloads/op` only when explicitly needed.
- **Consequences:** All Gradle builds, Git worktrees, CMake native tests, and Phase 0 experiments execute cleanly on a native POSIX filesystem.

## ADR-0002 — Initial Pinned Stack & Build Versions
- **Date:** 2026-11-26
- **Status:** Proposed (to be verified against actual Gradle sync/compile)
- **Context:** Need mutually compatible, current, OSS-licensed Kotlin/Android/JVM tooling checked against official Maven repositories on 2026-11-26.
- **Decision:**
  - Gradle: `9.9.2` (official release)
  - AGP: `9.5.3` (Google Maven latest stable; AGP 9 has built-in Kotlin compilation)
  - Kotlin: `2.4.21` (Maven Central latest stable, aligned with `adev-new` tested baseline)
  - `compileSdk`: `37`, `targetSdk`: `37`, `minSdk`: `26`
  - Compose BOM: `2026.09.00` (verified baseline for `compileSdk = 37`, or `2026.11.01`)
  - Coroutines: `1.11.0`, `kotlinx-serialization-json`: `1.13.0`, OkHttp (+ `mockwebserver3`): `5.5.1`
  - JUnit 5 / Jupiter: `6.1.3` (or `junit-jupiter` 5.x/6.x verified on Gradle 9.9.2)
  - AndroidX WebKit: `1.18.0`, Shizuku API: `13.1.5` (deferred to gated capability probe)
- **Consequences:** Versions are pinned in `gradle/libs.versions.toml` and recorded in `docs/DEPENDENCIES.md`.

## ADR-0003 — Module Boundaries & Dependency Direction
- **Date:** 2026-11-26
- **Status:** Accepted
- **Context:** Spec §3.1 & §3.3 mandate a modular monolith where dependencies point inward toward core contracts and core has zero Android imports.
- **Decision:** Start with five Gradle modules (`:core:contracts`, `:core:kernel`, `:core:models`, `:execution:broker`, `:app`). `:core:contracts` has zero external runtime dependencies beyond Kotlin stdlib + `kotlinx-serialization` + `kotlinx-coroutines`. `:app` is the Android composition root. Additional modules (`:execution:flowcode`, `:platform:*`, `:capabilities:*`, `:ui:*`) will be split out in Phase 2+ as concrete implementations justify boundaries.
- **Consequences:** Pure JVM unit tests run fast without Android framework overhead, and dependency direction is mechanically enforceable.

## ADR-0004 — QuickJS-NG & Isolated Execution Posture
- **Date:** 2026-11-26
- **Status:** Accepted
- **Context:** Spec §6.2 & §7.2 require proving QuickJS-NG async Promise scheduling, memory/interrupt limits, and process/identity isolation before enabling model-generated JS in production.
- **Decision:** Vendor/pin `quickjs-ng/quickjs` `v0.18.0` (MIT) in `experiments/quickjs-poc/` and build both a native Linux host test harness and an Android NDK JNI test library + `android:isolatedProcess="true"` service boundary skeleton (using standard Kotlin `Binder`/`Parcel` IPC so we do not rely on `build-tools/36.0.0/aidl`, which is x86-64 only on this ARM64 host). Keep untrusted JS execution disabled by feature flag in production until Phase 2 acceptance gates pass.
- **Consequences:** Avoids x86-64 `aidl` host blocker while keeping Binder/AIDL-equivalent strict typed IPC and true OS `isolatedProcess` UID separation.

## ADR-0005 — ARM64 Build-Tools Constraints (`aidl`, `zipalign`)
- **Date:** 2026-11-26
- **Status:** Accepted
- **Context:** Direct inspection of `/root/android-sdk/build-tools/36.0.0/` showed `aapt2` (ARM64 symlink), `d8` (Java), and `apksigner` (Java) work natively on ARM64, whereas `aidl` and `zipalign` ELF binaries are x86-64 and fail with `required file not found`.
- **Decision:** Do not enable `buildFeatures { aidl = true }` while building on ARM64 without a verified ARM64 `aidl` binary; use typed `android.os.Binder` + `Parcel` transaction contracts directly in Kotlin/Java for isolated IPC. Rely on AGP's Java-based `zipflinger` packaging rather than external `zipalign` binary. Record `aidl`/`zipalign` in `docs/COMPATIBILITY.md`.
- **Consequences:** `:app` compiles and assembles debug APKs natively on ARM64 without x86-64 emulation hacks.

## ADR-0006 — On-Device `adb` / Device Testing Gate
- **Date:** 2026-11-26
- **Status:** Accepted
- **Context:** User confirmed to build and run host/JVM/native tests + `assembleDebug` APK first and ask again before running any `adb` install or on-device tests.
- **Decision:** Phase 0 verifies everything testable on host JVM, native ARM64 Linux, and APK compilation (`assembleDebug`), while honestly marking physical-device runtime checks (Shizuku binder, Accessibility, MediaProjection, `isolatedProcess` UID verification on Android OS, APK install/launch) as `BLOCKED_AWAITING_DEVICE_CONSENT` until the user explicitly authorizes `adb` testing.
- **Consequences:** Zero unauthorized phone/ADB actions; complete transparency in `IMPLEMENTATION_STATUS.md` and `COMPATIBILITY.md`.
