# Android SDK On-Device Build Feasibility (Phase 0)

## Observed Toolchain (`adev` Ubuntu 24.04 ARM64)
- **JDK:** OpenJDK `21.0.13.1` (`java-21-openjdk-arm64`) — native ARM64, works.
- **AAPT2:** `/data/data/ai.antigem/files/usr/bin/aapt2` (`2.20-android-16.0.0_r4`) — native ARM64, works (`android.aapt2FromMavenOverride`).
- **D8 & apksigner:** Java wrappers in `/root/android-sdk/build-tools/36.0.0/` (`D8 8.10.9-dev`, `apksigner 0.9`) — run on ARM64 JVM.
- **NDK & CMake:** Google NDK `28.2.13676358` rebuilt by `adev` with Ubuntu ARM64 LLVM `19.1.1` + `lld` + `cmake 3.28.3` + `ninja 1.11.1` — compiles `arm64-v8a` `.so` libraries natively.
- **x86-64 leftovers in `build-tools/36.0.0`:** `aapt`, `aidl`, `zipalign`, `split-select`, `dexdump`, `bcc_compat`, `llvm-rs-cc` are x86-64 ELFs and fail direct execution (`required file not found`).

## Implications for Phase 4 (Oneflow App-Private Android Kit)
1. **No direct dependency on `aidl` binary:** Typed Binder `Parcel` contracts compile via `javac`/`kotlinc` without `aidl` binary.
2. **Packaging:** AGP `zipflinger` builds/aligns APKs in Java without external `zipalign`.
3. **Standalone In-App Android Kit (`Phase 4`):** Requires shipping or verifying signed ARM64 native executables (`aapt2`, optional `zipalign`/`aidl` from Apache-2.0 `lzhiyong/android-sdk-tools` `35.0.2`, ARM64 OpenJDK) in a way compliant with modern Android `targetSdk` W^X rules (e.g., `jniLibs`/APK native libs or verified user-approved runtime/remote worker). Until verified in Phase 4, the in-app Android kit remains **DISABLED**.
