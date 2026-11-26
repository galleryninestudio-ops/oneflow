# Oneflow Implementation Status

## Current Milestone Summary (§18.1 Format)
- **Phase / milestone:** Phase 5 — FlowWeb, FlowBrowser & Research (v0.4)
- **State:** `VERIFIED` (SSRF-guarded `SsrfPolicyValidator`, `SourceCircuitBreaker`, `WikipediaOpenSearchAdapter`, `SearchRouter`, `WebFetcher` + `jsoup` extraction, `ResearchLedger` citations, `BrowserRefRegistry` / `DomSnapshot`, `ResearchTab` + isolated WebView Browser Space, `lintDebug`, and Shizuku `rish` device install)
- **Git commit / branch:** `main` (`origin`: `https://github.com/galleryninestudio-ops/oneflow`)
- **Implemented real capabilities:**
  - `:capabilities:research`: `SsrfPolicyValidator` (blocks non-HTTP/S, DNS loopback/RFC1918/link-local/ULA), `SourceCircuitBreaker`, `WikipediaOpenSearchAdapter`, `SearchRouter` (URL deduplication), `WebFetcher` (OkHttp + `jsoup` readable text extraction), `ResearchLedger` (grounded claims & citations), `FlowWebTools` (`web.search`, `web.fetch`, `research.report`), `BrowserRefRegistry` & `DomSnapshot`.
  - `:app`: `OneflowSessionController` (`runWebSearch`, `fetchAndCiteUrl`), `ResearchTab` with search results, direct URL fetch/citation report preview, and SSRF-guarded WebView Browser Space with `Manual Takeover` pause toggle and `WebViewFeature.MULTI_PROFILE` capability check.
- **Acceptance criteria passed / failed / not run:**
  - Passed: `35/35` tests across 13 suites, `:app:lintDebug`, `:app:assembleDebug` (~15M `app-debug.apk`), pure-core import check (`PURE_CORE_OK`), and `rish` device install/launch (`WaitTime: 482ms`).
- **Commands and actual test results:** Recorded in `docs/TEST_EVIDENCE.md`.
- **Environment (host, Android SDK/target, ABI, device, provider):** Linux `6.12.69` `aarch64` Android 16; `adev` Ubuntu 24.04; OpenJDK `21.0.13.1`; Gradle `9.9.2`; AGP `9.5.3`; NDK `28.2.13676358`.
- **Evidence paths / artifacts / screenshots:** `docs/TEST_EVIDENCE.md`, `app/build/outputs/apk/debug/app-debug.apk`.
- **Upstream sources, pinned versions, licenses:** Recorded in `docs/DEPENDENCIES.md` (including `jsoup:1.24.1`).
- **Security and privacy checks:** `SsrfPolicyValidator` blocks `file://`, `127.0.0.1`, `169.254.169.254`, and `10.x` RFC1918 targets; WebView Browser Space disables `javaScriptEnabled`, `allowFileAccess`, `allowContentAccess`, and `domStorageEnabled` by default.
- **Known blockers, limitations, assumptions:** Live external LLM API test still requires a user-supplied key.
- **Deviations from this spec and ADR link:** `ADR-0001`..`ADR-0006` in `docs/DECISIONS.md`.
- **Next smallest vertical slice / ticket:** `OF-0007` — Phase 6: Android Computer Use (v0.5) (`FlowAccess` interaction router, Shizuku `UserService` Binder IPC, `OneflowAccessibilityService`, `MediaProjection` consent lifecycle, emergency stop UI).

---

## Phase Checklist
- **Phase 0 — Feasibility and foundation (v0.0.1):** `VERIFIED`
- **Phase 1 — Own agent kernel + BYO API (v0.1-alpha):** `VERIFIED`
- **Phase 2 — FlowCode secure execution (v0.1):** `VERIFIED`
- **Phase 3 — Intelligence, state and reliability (v0.2):** `VERIFIED`
- **Phase 4 — Native development environment (v0.3):** `VERIFIED`
- **Phase 5 — FlowWeb, FlowBrowser, research (v0.4):** `VERIFIED`
- **Phase 6 — Android computer use (v0.5):** `NOT_STARTED`
- **Phase 7 — REA, FlowForge, FlowTeach (v0.6):** `NOT_STARTED`
- **Phase 8 — Teams, workflows, learning and remote (v0.7):** `NOT_STARTED`
- **Phase 9 — Complete polished workspace (v0.8):** `NOT_STARTED`
- **Phase 10 — Production engineering and release (v0.9 → v1.0):** `NOT_STARTED`
