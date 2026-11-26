# Phase 0 Feasibility Experiments

> All items in `experiments/` are isolated Phase 0 proofs-of-concept. Untrusted JS execution, agent shells, and privileged Android APIs remain disabled by feature flags in `:app` until their respective phase gates pass.

1. `quickjs-poc/` — QuickJS-NG `v0.18.0` (`d7215b0ad3fc`, MIT) host C harness + Android NDK `arm64-v8a` JNI shared library (`liboneflow_qjs_jni.so`), proving `async`/`await` Promise job pumping, host capability callback promises, error propagation, `JS_SetMemoryLimit`, uncatchable `JS_SetInterruptHandler` timeout interruption, and absence of Node/QuickJS `std`/`os`/`require`.
2. `isolated-worker-poc/` — Linux host child-process environment scrubbing, FD closing, `RLIMIT_*`, and `SIGKILL` watchdog containment, paired with `app/.../IsolatedWorkerProbeService` (`android:isolatedProcess="true"` + Binder `Parcel` IPC) demonstrating why same-UID Linux subprocesses are not a file sandbox while Android `isolatedProcess` enforces OS UID isolation.
3. `native-exec-poc/` — ARM64 Bionic (`aarch64-linux-android26-clang`) ELF inspection (`interpreter /system/bin/linker64`), verifying execution on POSIX `f2fs` vs `Permission denied` on `/dev/fuse` (`noexec`) and documenting Android 10+ `targetSdk >= 29` W^X constraints.
4. `android-sdk-poc/` — Audit of on-device ARM64 Android SDK build tools (`OpenJDK 21`, `aapt2`, `d8`, `apksigner` vs x86-64 `aidl`/`zipalign`, plus `lzhiyong/android-sdk-tools` static ARM64 assessment).
5. `rea-a2ui-probe/` — Local `rea-agents@6.10.1` CLI/doctor/capability audit on ARM64 Linux and A2UI `v0.9`/`v1.0` specification assessment.
