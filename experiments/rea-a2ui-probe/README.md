# REA & A2UI Phase 0 Probe Findings

## 1. REA (`morluto/rea`, `rea-agents@6.10.1`, MIT)
- Tested inside `adev` Ubuntu 24.04 ARM64 (`Node v24.22.3`).
- `npx --yes rea-agents@6.10.1 --help` -> exit 0 (`204` CLI commands, `263` MCP tools, `6` prompts).
- `npx --yes rea-agents@6.10.1 doctor` & `capabilities` -> exit 0:
  - **Available locally:** JS-native artifact/archive graph inspection (`rea-artifact-graph`).
  - **Unsupported on Android/Linux:** `native-macos`, `hopper` (`unsupported_host`).
  - **Requires external engine/config:** `ghidra` (`GHIDRA_INSTALL_DIR >= 12.2`), `jadx` (`REA_JADX_MCP_JAR` `jadx-headless-mcp-0.7.1-all.jar`), `ida` (`REA_IDA_MCP_CONFIG`), `kuna` (`REA_KUNA_COMMAND`).
- **Termux native tools observed:** `rizin 0.9.1 @ linux-arm-64`, `readelf`, `llvm-objdump`.
- **Phase 0 conclusion:** REA core JS/MCP orchestration runs on ARM64 Linux Node 24, while heavy decompilers (Ghidra/JADX/IDA) require explicit local/remote configuration. `builder.rea.enabled` stays `false` until Phase 7.

## 2. A2UI (`a2ui-project/a2ui`, Apache-2.0)
- Inspected upstream repo `36725eee042d` (`v0.12.0+swift`).
- `specification/` defines `v0_8`, `v0_9`, `v0_9_1`, `v1_0`.
- `kotlin/` contains only `agent_sdk_legacy`; no maintained Jetpack Compose renderer library is published upstream.
- **Phase 0 conclusion:** Implemented Oneflow's own validated A2UI subset parser & Compose renderer (`ai.oneflow.app.a2ui.A2UiValidator` & `A2UiSubsetView`) with strict component allowlist, depth/node caps, cycle rejection, and unit tests in `A2UiValidatorTest`.
