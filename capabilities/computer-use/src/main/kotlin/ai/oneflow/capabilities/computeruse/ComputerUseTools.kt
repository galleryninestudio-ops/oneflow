package ai.oneflow.capabilities.computeruse

import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.RiskTier
import ai.oneflow.core.contracts.ToolManifest
import ai.oneflow.core.contracts.ToolResultStatus
import ai.oneflow.execution.broker.BrokerToolException
import ai.oneflow.execution.broker.SafeToolBroker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Registers Phase 6 FlowAccess computer-use tools with SafeToolBroker.
 */
class ComputerUseTools(
    private val router: FlowAccessRouter
) {
    private fun schema(raw: String) = Json.parseToJsonElement(raw).jsonObject

    fun registerAll(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "device.ui_tree",
                version = "1.0.0",
                description = "Capture sanitized active-window UI semantic tree with epoch-scoped refs",
                inputSchema = schema("""{"type":"object","properties":{},"additionalProperties":false}"""),
                requiredCapabilities = setOf("device.ui.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("device", "accessibility", "ui")
            )
        ) {
            val snap = router.captureSnapshot().getOrElse { err ->
                throw BrokerToolException(ToolResultStatus.FAILED, ErrorCode.FAILED, err.message ?: "UI capture failed")
            }
            buildJsonObject {
                put("epoch", JsonPrimitive(snap.epoch))
                put("packageName", JsonPrimitive(snap.packageName))
                put("windowTitle", JsonPrimitive(snap.windowTitle))
                put("redactedCount", JsonPrimitive(snap.redactedCount))
                put("nodes", buildJsonArray {
                    for (n in snap.nodes) {
                        add(buildJsonObject {
                            put("refId", JsonPrimitive(n.refId))
                            put("className", JsonPrimitive(n.className))
                            put("text", JsonPrimitive(n.text))
                            put("contentDescription", JsonPrimitive(n.contentDescription))
                            put("clickable", JsonPrimitive(n.clickable))
                            put("editable", JsonPrimitive(n.editable))
                        })
                    }
                })
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "device.tap_ref",
                version = "1.0.0",
                description = "Tap an active-window UI element by epoch + refId",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "epoch": { "type": "integer", "minimum": 1 },
                        "refId": { "type": "string", "minLength": 1 }
                      },
                      "required": ["epoch", "refId"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("device.actuate"),
                risk = RiskTier.EXTERNAL_SIDE_EFFECT,
                tags = setOf("device", "actuate")
            )
        ) { req ->
            val epoch = req.arguments["epoch"]?.jsonPrimitive?.longOrNull
                ?: throw BrokerToolException(ToolResultStatus.FAILED, ErrorCode.FAILED, "Missing epoch")
            val refId = req.arguments["refId"]?.jsonPrimitive?.content
                ?: throw BrokerToolException(ToolResultStatus.FAILED, ErrorCode.FAILED, "Missing refId")
            val res = router.tapRef(epoch, refId)
            if (!res.ok) {
                val isDenied = res.errorCode == "EMERGENCY_STOPPED" || res.errorCode == "SENSITIVE_FIELD" || res.errorCode == "PROTECTED_UI"
                throw BrokerToolException(
                    if (isDenied) ToolResultStatus.DENIED else ToolResultStatus.FAILED,
                    if (isDenied) ErrorCode.DENIED else ErrorCode.FAILED,
                    res.message
                )
            }
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("message", JsonPrimitive(res.message))
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "device.input_text",
                version = "1.0.0",
                description = "Input text into a non-sensitive editable UI element by epoch + refId",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "epoch": { "type": "integer", "minimum": 1 },
                        "refId": { "type": "string", "minLength": 1 },
                        "text": { "type": "string" }
                      },
                      "required": ["epoch", "refId", "text"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("device.actuate"),
                risk = RiskTier.EXTERNAL_SIDE_EFFECT,
                tags = setOf("device", "actuate")
            )
        ) { req ->
            val epoch = req.arguments["epoch"]?.jsonPrimitive?.longOrNull
                ?: throw BrokerToolException(ToolResultStatus.FAILED, ErrorCode.FAILED, "Missing epoch")
            val refId = req.arguments["refId"]?.jsonPrimitive?.content
                ?: throw BrokerToolException(ToolResultStatus.FAILED, ErrorCode.FAILED, "Missing refId")
            val text = req.arguments["text"]?.jsonPrimitive?.content
                ?: throw BrokerToolException(ToolResultStatus.FAILED, ErrorCode.FAILED, "Missing text")
            val res = router.inputText(epoch, refId, text)
            if (!res.ok) {
                throw BrokerToolException(ToolResultStatus.DENIED, ErrorCode.DENIED, res.message)
            }
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("message", JsonPrimitive(res.message))
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "device.global_action",
                version = "1.0.0",
                description = "Perform global navigation action (BACK, HOME, RECENTS)",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "action": { "type": "string", "enum": ["BACK", "HOME", "RECENTS"] }
                      },
                      "required": ["action"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("device.actuate"),
                risk = RiskTier.EXTERNAL_SIDE_EFFECT,
                tags = setOf("device", "actuate")
            )
        ) { req ->
            val raw = req.arguments["action"]?.jsonPrimitive?.content
                ?: throw BrokerToolException(ToolResultStatus.FAILED, ErrorCode.FAILED, "Missing action")
            val action = GlobalDeviceAction.valueOf(raw)
            val res = router.globalAction(action)
            if (!res.ok) throw BrokerToolException(ToolResultStatus.DENIED, ErrorCode.DENIED, res.message)
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("message", JsonPrimitive(res.message))
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "device.screen_capture_status",
                version = "1.0.0",
                description = "Inspect MediaProjection consent and Shizuku read-only bridge status",
                inputSchema = schema("""{"type":"object","properties":{},"additionalProperties":false}"""),
                requiredCapabilities = setOf("device.ui.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("device", "status")
            )
        ) {
            val mp = router.getMediaProjectionState()
            val sh = router.getShizukuStatus()
            buildJsonObject {
                put("emergencyStopped", JsonPrimitive(router.isEmergencyStopped()))
                put("mediaProjectionStatus", JsonPrimitive(mp.status.name))
                put("shizukuAvailable", JsonPrimitive(sh.available))
                put("shizukuPermissionGranted", JsonPrimitive(sh.permissionGranted))
                put("shizukuUserServiceBound", JsonPrimitive(sh.userServiceBound))
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "device.emergency_stop",
                version = "1.0.0",
                description = "Immediately halt all device actuation and revoke MediaProjection",
                inputSchema = schema("""{"type":"object","properties":{"reason":{"type":"string"}},"additionalProperties":false}"""),
                requiredCapabilities = emptySet(),
                risk = RiskTier.READ_ONLY,
                tags = setOf("device", "safety")
            )
        ) { req ->
            val reason = req.arguments["reason"]?.jsonPrimitive?.content ?: "Tool emergency stop"
            router.triggerEmergencyStop(reason)
            buildJsonObject {
                put("emergencyStopped", JsonPrimitive(true))
                put("reason", JsonPrimitive(reason))
            }
        }
    }
}
