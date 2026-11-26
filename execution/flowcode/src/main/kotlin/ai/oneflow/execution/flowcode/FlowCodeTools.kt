package ai.oneflow.execution.flowcode

import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.MissionStore
import ai.oneflow.core.contracts.ModelToolSpec
import ai.oneflow.core.contracts.RiskTier
import ai.oneflow.core.contracts.ScriptEngine
import ai.oneflow.core.contracts.ScriptRequest
import ai.oneflow.core.contracts.ToolManifest
import ai.oneflow.core.contracts.ToolQuery
import ai.oneflow.core.contracts.ToolRegistry
import ai.oneflow.core.contracts.ToolResultStatus
import ai.oneflow.execution.broker.BrokerToolException
import ai.oneflow.execution.broker.SafeToolBroker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Registers the default Code Mode model tools (`discover` and `execute`, §5.3)
 * backed by `ToolRegistry` and `ScriptEngine`.
 */
object FlowCodeTools {

    fun registerMetaTools(
        broker: SafeToolBroker,
        registry: ToolRegistry,
        scriptEngine: ScriptEngine,
        missionStore: MissionStore? = null
    ): List<ModelToolSpec> {
        val discoverManifest = ToolManifest(
            id = "discover",
            version = "1.0.0",
            description = "Find authorized tool schemas, TypeScript SDK signatures, and examples",
            inputSchema = Json.parseToJsonElement(
                """
                {
                  "type": "object",
                  "properties": {
                    "query": { "type": "string" },
                    "limit": { "type": "integer", "minimum": 1, "maximum": 20 }
                  },
                  "required": ["query"],
                  "additionalProperties": false
                }
                """.trimIndent()
            ).jsonObject,
            requiredCapabilities = emptySet(),
            risk = RiskTier.READ_ONLY,
            tags = setOf("flowcode", "discover")
        )

        val executeManifest = ToolManifest(
            id = "execute",
            version = "1.0.0",
            description = "Execute bounded FlowCode JavaScript using authorized `flow.*` capabilities",
            inputSchema = Json.parseToJsonElement(
                """
                {
                  "type": "object",
                  "properties": {
                    "code": { "type": "string", "minLength": 1 }
                  },
                  "required": ["code"],
                  "additionalProperties": false
                }
                """.trimIndent()
            ).jsonObject,
            requiredCapabilities = emptySet(),
            risk = RiskTier.READ_ONLY,
            tags = setOf("flowcode", "execute")
        )

        broker.registerTool(discoverManifest) { req ->
            val q = req.arguments["query"]!!.jsonPrimitive.content
            val limit = req.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 10
            val defs = registry.discover(ToolQuery(query = q, limit = limit))
                .filter { it.id != "discover" && it.id != "execute" }
            val sdk = FlowSdkGenerator.generateCompactDeclarations(defs)
            buildJsonObject {
                put("count", JsonPrimitive(defs.size))
                put("tools", buildJsonArray {
                    for (d in defs) {
                        add(buildJsonObject {
                            put("id", JsonPrimitive(d.id))
                            put("version", JsonPrimitive(d.version))
                            put("description", JsonPrimitive(d.description))
                            put("risk", JsonPrimitive(d.risk.name))
                            put("example", JsonPrimitive(d.exampleUsage ?: ""))
                        })
                    }
                })
                put("sdkDeclarations", JsonPrimitive(sdk))
            }
        }

        broker.registerTool(executeManifest) { req ->
            val code = req.arguments["code"]!!.jsonPrimitive.content
            val res = scriptEngine.execute(
                ScriptRequest(
                    missionId = req.missionId,
                    script = code
                )
            )
            if (!res.ok) {
                val status = when (res.error?.code) {
                    ErrorCode.TIMEOUT -> ToolResultStatus.TIMEOUT
                    ErrorCode.DENIED -> ToolResultStatus.DENIED
                    else -> ToolResultStatus.FAILED
                }
                throw BrokerToolException(status, res.error?.code ?: ErrorCode.FAILED, res.error?.message ?: "Script failed")
            }
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("durationMs", JsonPrimitive(res.durationMs))
                put("output", JsonPrimitive(res.outputJson ?: "null"))
            }
        }

        if (missionStore != null) {
            broker.registerTool(
                ToolManifest(
                    id = "mission.status",
                    version = "1.0.0",
                    description = "Inspect current mission state, revision, and budget usage",
                    inputSchema = Json.parseToJsonElement(
                        """{"type":"object","properties":{},"additionalProperties":false}"""
                    ).jsonObject,
                    requiredCapabilities = emptySet(),
                    risk = RiskTier.READ_ONLY,
                    tags = setOf("mission", "status")
                )
            ) { req ->
                val snap = missionStore.load(req.missionId)
                    ?: throw BrokerToolException(ToolResultStatus.UNAVAILABLE, ErrorCode.UNAVAILABLE, "Mission not found")
                buildJsonObject {
                    put("missionId", JsonPrimitive(snap.id.value))
                    put("state", JsonPrimitive(snap.state.name))
                    put("stateRevision", JsonPrimitive(snap.stateRevision))
                    put("modelTurnsUsed", JsonPrimitive(snap.usage.modelTurnsUsed))
                    put("toolCallsUsed", JsonPrimitive(snap.usage.toolCallsUsed))
                }
            }
        }

        return listOf(discoverManifest, executeManifest).map { m ->
            ModelToolSpec(m.id, m.description, m.inputSchema)
        }
    }
}
