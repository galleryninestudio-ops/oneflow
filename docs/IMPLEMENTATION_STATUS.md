# Oneflow Implementation Status

## Current Milestone Summary (§18.1 Format)
- **Phase / milestone:** Phase 6 — Android Computer Use (v0.5)
- **State:** `VERIFIED` (`:capabilities:computer-use` `FlowAccessRouter` + `ComputerUseTools`, `OneflowAccessibilityService`, `ShizukuBridge` raw Binder UserService, `MediaProjectionConsentManager`, Emergency Stop & onboarding UI, `lintDebug`, and Shizuku `rish` device install/cold-launch)
- **Git commit / branch:** `main` (`origin`: `https://github.com/galleryninestudio-ops/oneflow`)
- **Implemented real capabilities:**
  - `:capabilities:computer-use`: `FlowAccessRouter` (epoch-scoped `UiTreeSnapshot`/`UiNodeRef`, stale ref rejection `STALE_UI_REF`, password/sensitive node redaction, protected package redaction, `EMERGENCY_STOPPED` kill-switch, `MediaProjectionConsentStatus` & `ShizukuBridgeStatus` tracking) and `ComputerUseTools` (`device.ui_tree`, `device.tap_ref`, `device.input_text`, `device.global_action`, `device.screen_capture_status`, `device.emergency_stop`).
  - `:app`: `OneflowAccessibilityService` (`BIND_ACCESSIBILITY_SERVICE`), `ShizukuBridge` (`dev.rikka.shizuku:api:13.1.5` + `provider:13.1.5` with `OneflowShizukuUserService` raw `Binder` IPC), `MediaProjectionConsentManager`, and `SetupTab` Emergency Stop + permission onboarding controls.
- **Acceptance criteria passed / failed / not run:**
  - Passed: `39/39` tests across 14 suites, `:app:lintDebug`, `:app:assembleDebug`, pure-core import check (`PURE_CORE_OK`), and `rish` device install/cold-launch (`TotalTime: 1260ms`).
- **Commands and actual test results:** Recorded in `docs/TEST_EVIDENCE.md`.
- **Environment (host, Android SDK/target, ABI, device, provider):** Linux `6.12.69` `aarch64` Android 16; `adev` Ubuntu 24.04; OpenJDK `21.0.13.1`; Gradle `9.9.2`; AGP `9.5.3`; NDK `28.2.13676358`.
- **Evidence paths / artifacts / screenshots:** `docs/TEST_EVIDENCE.md`, `app/build/outputs/apk/debug/app-debug.apk`.
- **Upstream sources, pinned versions, licenses:** Recorded in `docs/DEPENDENCIES.md` (including `dev.rikka.shizuku:api:13.1.5`).
- **Security and privacy checks:** Password nodes (`isPassword=true`) & protected packages (`com.android.systemui`, etc.) redacted and blocked from actuation (`SENSITIVE_FIELD`, `PROTECTED_UI`); stale refs blocked (`STALE_UI_REF`); `EMERGENCY STOP` halts actuation and revokes `MediaProjection`; `SafeToolBroker` enforces `device.actuate` capability grant.
- **Known blockers, limitations, assumptions:** Live external LLM API test still requires a user-supplied key; Accessibility Service & MediaProjection consent require interactive user toggle in Android Settings / system dialog.
- **Deviations from this spec and ADR link:** `ADR-0001`..`ADR-0006` in `docs/DECISIONS.md`.
- **Next smallest vertical slice / ticket:** `OF-0008` — Phase 7: REA, FlowForge & FlowTeach (v0.6) (`:capabilities:builder` with `rea-agents@6.10.1` adapter, quarantined generator + static/security checks + registry/rollback, FlowTeach recording -> FlowCode skill, Agent-Native App SDK template).

---

## Phase Checklist
- **Phase 0 — Feasibility and foundation (v0.0.1):** `VERIFIED`
- **Phase 1 — Own agent kernel + BYO API (v0.1-alpha):** `VERIFIED`
- **Phase 2 — FlowCode secure execution (v0.1):** `VERIFIED`
- **Phase 3 — Intelligence, state and reliability (v0.2):** `VERIFIED`
- **Phase 4 — Native development environment (v0.3):** `VERIFIED`
- **Phase 5 — FlowWeb, FlowBrowser, research (v0.4):** `VERIFIED`
- **Phase 6 — Android computer use (v0.5):** `VERIFIED`
- **Phase 7 — REA, FlowForge, FlowTeach (v0.6):** `NOT_STARTED`
- **Phase 8 — Teams, workflows, learning and remote (v0.7):** `NOT_STARTED`
- **Phase 9 — Complete polished workspace (v0.8):** `NOT_STARTED`
- **Phase 10 — Production engineering and release (v0.9 → v1.0):** `NOT_STARTED`
