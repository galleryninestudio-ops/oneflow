# Oneflow Test & Verification Evidence

> Policy: Record only directly observed commands, environments, exit codes, and outputs. Distinguish mock tests, host tests, emulator tests, and physical-device tests.

## Phase 0 — Environment & Feasibility Probes (2026-11-26)

### ENV-001: Filesystem & FUSE Probe (`downloads/op` vs `~/projects`)
- **Environment:** Host Android 16 kernel `6.12.69` `aarch64`
- **Command:** `ln -s a .../op/.probe_tmp/link` and `git init` in `.../op/.probe_tmp`
- **Observed Result:**
  - `/dev/fuse` mounted with `noexec`; `chmod 755` ignored (`-rw-rw----`); `ln -s` failed with `Permission denied`; `git config` failed with `fatal: not in a git directory`.
  - `/data/user/0/ai.antigem/files/home` resides on `/dev/block/dm-99` (`f2fs`), where `git init -b main` succeeded cleanly (`~/projects/oneflow`).

### ENV-002: Android SDK & Build-Tools ARM64 Probe
- **Environment:** `adev` Ubuntu 24.04 `aarch64` container
- **Command:** `adev 'for f in /root/android-sdk/build-tools/36.0.0/*; do [ -f "$f" ] && file "$f"; done'` + version invocations
- **Observed Result:**
  - `openjdk version "21.0.13.1"`, `javac 21.0.13.1`
  - `cmake version 3.28.3`, `ninja 1.11.1`, NDK `28.2.13676358` (`Ubuntu clang version 19.1.1`)
  - `aapt2`: `Android Asset Packaging Tool (aapt) 2.20-android-16.0.0_r4` (exit 0)
  - `d8`: `D8 8.10.9-dev` (exit 0)
  - `apksigner`: `0.9` (exit 0)
  - `aidl` & `zipalign`: `ELF 64-bit LSB pie executable, x86-64` -> fails direct execution (`cannot execute: required file not found`)
