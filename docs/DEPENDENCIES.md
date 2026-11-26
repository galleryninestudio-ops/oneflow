# Oneflow Dependency & Upstream Research Inventory

**Last verified:** 2026-11-26

> Policy: Pin versions, record exact URLs/tags/commits/licenses, and verify license compatibility before shipping. Never incorporate GPL/AGPL code into Oneflow without explicit licensing approval.

| Component | Upstream / Artifact | Pinned Version / Ref | License | Role & Notes |
|---|---|---|---|---|
| Gradle | `https://services.gradle.org/distributions/gradle-9.9.2-bin.zip` | `9.9.2` (`971fcd7150ca4ba0...`) | Apache-2.0 | Build tool |
| Android Gradle Plugin | `com.android.tools.build:gradle` | `9.5.3` | Apache-2.0 | Android build & built-in Kotlin compiler |
| Kotlin | `org.jetbrains.kotlin:*` | `2.4.21` | Apache-2.0 | JVM & Android Kotlin language/compiler |
| Kotlin Coroutines | `https://github.com/Kotlin/kotlinx.coroutines` | `1.11.0` (`8564f65764d3`) | Apache-2.0 | Structured concurrency |
| Kotlin Serialization | `https://github.com/Kotlin/kotlinx.serialization` | `1.13.0` (`0cd830bb43a2`) | Apache-2.0 | JSON & schema serialization |
| OkHttp + MockWebServer | `https://github.com/square/okhttp` | `5.5.1` (`be5151ac3b63`) | Apache-2.0 | HTTP/TLS client, SSE streaming, mock HTTP server |
| Jetpack Compose BOM | `androidx.compose:compose-bom` | `2026.09.00` (pinned for `compileSdk = 37` / `android-37.0`; `2026.11.01` requires `android-37.1`) | Apache-2.0 | Native Android UI |
| AndroidX Core / Activity / Lifecycle / Navigation | `androidx.core:core-ktx`, `activity-compose`, `lifecycle-runtime-ktx`, `navigation-compose` | `1.19.1`, `1.13.0`, `2.11.0`, `2.10.2` | Apache-2.0 | Android platform & navigation |
| JUnit 5 (Jupiter) | `org.junit.jupiter:junit-jupiter` | `6.1.3` (verify JVM test runner on Gradle 9.9.2) | EPL-2.0 | Host unit & contract tests |
| QuickJS-NG | `https://github.com/quickjs-ng/quickjs` | `v0.18.0` (`d7215b0ad3fc`) | MIT | Phase 0/2 embedded JS engine candidate |
| AndroidX WebKit | `androidx.webkit:webkit` | `1.18.0` | Apache-2.0 | Phase 0/5 WebViewCompat & ProfileStore probes |
| Shizuku API | `https://github.com/RikkaApps/Shizuku-API` (`dev.rikka.shizuku:api`) | `13.1.5` (repo tag `v12.1.0`, head `5ab1d6f844ca`) | MIT | Phase 0/6 optional privileged Android bridge |
| REA | `https://github.com/morluto/rea` (`rea-agents`) | `6.10.1` (`8bb01b514ac7`, head `6991413d41bf`) | MIT | Phase 0/7 reverse engineering CLI/MCP toolkit |
| A2UI | `https://github.com/a2ui-project/a2ui` | `v0.12.0+swift` / spec `v0_9`, `v1_0` (`36725eee042d`) | Apache-2.0 | Generative UI schema; `kotlin/` only has `agent_sdk_legacy`, so implement validated Compose subset |
| Ego Lite | `https://github.com/citrolabs/ego-lite` | `v2.2.1` (`f499162d1b91`) | MIT | Architecture study for browser spaces, snapshots, ref registry |
| SearchMob | `https://github.com/FlintWave/SearchMob` | `v26.11.02` (`e9ee5a414f47`) | **AGPL-3.0** | Reference study only — **DO NOT COPY CODE** due to AGPL-3.0 |
| android-sdk-tools | `https://github.com/lzhiyong/android-sdk-tools` | `35.0.2` (`aa13480acd6c`) | Apache-2.0 | Reference for ARM64 Android SDK build-tools on Android |
| MCP Kotlin SDK | `https://github.com/modelcontextprotocol/kotlin-sdk` | `v0.7.0` (`9a70ceaaba99`) | MIT (verify license file before Phase 3/5) | Future MCP integration candidate |
