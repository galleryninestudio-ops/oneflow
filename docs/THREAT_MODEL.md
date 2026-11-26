# Oneflow Threat Model

**Last updated:** 2026-11-26 (Phase 0 baseline)

## 1. Protected Assets
1. **User Files & SAF Grants** — User workspace files, external storage URIs, and app-private storage outside authorized mission scope.
2. **Credentials & API Keys** — User-supplied LLM provider keys, OAuth/MCP tokens, remote worker keys (stored via Android Keystore / FlowVault, never exposed to model context, JS workers, logs, or traces).
3. **Device & System Control** — Android `AccessibilityService`, Shizuku `UserService`, `MediaProjection`, package installation, and settings.
4. **Browser Sessions & Cookies** — Personal WebView profiles/cookies and authenticated origins.
5. **Mission Integrity & Budget** — Authoritative mission state, user requirements revisions, acceptance criteria, spend/turn/tool budgets, and effect idempotency.

## 2. Trust Boundaries
- **TB-1: Untrusted Model Output ↔ FlowCore Kernel**: Model completions, tool arguments, and generated FlowCode JS are untrusted proposals. Kernel validates JSON schemas, mission state revisions, and budgets before dispatching.
- **TB-2: FlowCode JS Worker ↔ Host ToolBroker**: JS runtime executes without filesystem, socket, shell, environment variables, `std`/`os`, or raw secrets. Only JSON-serializable typed requests cross IPC; host enforces policy, path containment, size caps, and timeouts.
- **TB-3: External Content (Web/Repo/MCP/APKs) ↔ FlowContext**: Fetched web pages, `README`s, MCP server descriptions/results, and decompiled artifacts are untrusted data, never elevated to system instructions.
- **TB-4: Trusted App Process ↔ Isolated Native/JS Worker**: In Phase 2+, untrusted JS and native execution must run in an OS-isolated process (`android:isolatedProcess="true"` on Android) with separate UID and zero direct access to app-private files or network.
- **TB-5: Native App Approval UI ↔ Agent/A2UI Surface**: Sensitive approvals, permission grants, credential entry, and emergency stops render strictly in trusted native Compose UI, never A2UI or WebView HTML.

## 3. Primary Attack Classes & Required Controls
| Threat | Mitigation Strategy | Required Verification (Phase) |
|---|---|---|
| Prompt injection via fetched web/repo/MCP | Strict instruction layering in FlowContext; untrusted content delimited as data; default-deny broker | Phase 1/2/3 security tests |
| Malicious FlowCode JS (`while(true)`, OOM, prototype/host escape) | QuickJS-NG `JS_SetMemoryLimit`, `JS_SetMaxStackSize`, `JS_SetInterruptHandler`, watchdog process kill, no `std`/`os`/eval | Phase 0 POC + Phase 2 security suite |
| Path traversal / symlink / zip-slip | Canonical realpath containment checks against authorized workspace root; no unverified symlink traversal; bounded archive extraction | Phase 2 FS security tests |
| Secret leakage to model/logs/traces | Credential alias resolution only in trusted broker; secret/header/token redaction before model context and trace storage | Phase 1/2 redaction tests |
| SSRF / cross-origin credential leak | DNS/IP validation blocking loopback, private RFC1918, link-local, metadata IPs on each redirect; origin-bound auth | Phase 5 network policy tests |
| Duplicate irreversible side effects on crash/retry | Effect intent persisted prior to dispatch; idempotency keys & reconciliation; no blind retries | Phase 1/3 recovery tests |
| Confused deputy / stale UI target | Snapshot generation + package/window/node validation before acting; `STALE_STATE` rejection | Phase 5/6 interaction tests |
| Arbitrary shell / native binary execution | No generic shell without verified OS isolation; signed manifests & checksums for native kits; target SDK W^X compliance | Phase 0/4 runtime tests |
