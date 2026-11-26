package ai.oneflow.capabilities.builder

import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.RiskTier
import ai.oneflow.core.contracts.ToolManifest
import ai.oneflow.core.contracts.ToolResultStatus
import ai.oneflow.execution.broker.BrokerToolException
import ai.oneflow.execution.broker.SafeToolBroker
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

class BuilderTools(
    private val reaAdapter: ReaAdapter,
    private val forgeRegistry: FlowForgeRegistry,
    private val workspaceRoot: Path,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private fun schema(raw: String) = Json.parseToJsonElement(raw).jsonObject

    fun registerAll(broker: SafeToolBroker) {
        broker.registerTool(
            ToolManifest(
                id = "rea.capabilities",
                version = "1.0.0",
                description = "Probe rea-agents@6.10.1 CLI/engine availability without fabricating missing engines",
                inputSchema = schema("""{"type":"object","properties":{},"additionalProperties":false}"""),
                requiredCapabilities = setOf("builder.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("rea", "reverse-engineering")
            )
        ) {
            val status = reaAdapter.probeCapabilities(workspaceRoot)
            buildJsonObject {
                put("reaPackage", JsonPrimitive(status.reaPackage))
                put("cliAvailable", JsonPrimitive(status.cliAvailable))
                put("note", JsonPrimitive(status.note))
                put("missingEngines", buildJsonArray {
                    status.missingEngines.forEach { add(JsonPrimitive(it)) }
                })
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "forge.scan_and_register",
                version = "1.0.0",
                description = "Run static security checks and register a versioned FlowForge adapter",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "adapterId": { "type": "string", "minLength": 1 },
                        "sourceCode": { "type": "string", "minLength": 1 }
                      },
                      "required": ["adapterId", "sourceCode"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("builder.write"),
                risk = RiskTier.REVERSIBLE_WRITE,
                tags = setOf("forge", "security")
            )
        ) { req ->
            val id = req.arguments["adapterId"]!!.jsonPrimitive.content
            val code = req.arguments["sourceCode"]!!.jsonPrimitive.content
            when (val res = forgeRegistry.register(id, code)) {
                is ForgeRegisterResult.Rejected -> {
                    throw BrokerToolException(
                        ToolResultStatus.DENIED,
                        ErrorCode.DENIED,
                        "FlowForge static scan rejected: ${res.findings.joinToString { it.ruleId }}"
                    )
                }
                is ForgeRegisterResult.Registered -> {
                    buildJsonObject {
                        put("adapterId", JsonPrimitive(res.record.adapterId))
                        put("version", JsonPrimitive(res.record.version))
                        put("active", JsonPrimitive(res.record.active))
                    }
                }
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "forge.rollback",
                version = "1.0.0",
                description = "Roll back an active FlowForge adapter to its previous verified version",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": { "adapterId": { "type": "string", "minLength": 1 } },
                      "required": ["adapterId"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("builder.write"),
                risk = RiskTier.REVERSIBLE_WRITE,
                tags = setOf("forge", "rollback")
            )
        ) { req ->
            val id = req.arguments["adapterId"]!!.jsonPrimitive.content
            val rolled = forgeRegistry.rollback(id)
                ?: throw BrokerToolException(ToolResultStatus.FAILED, ErrorCode.FAILED, "No previous version for '$id'")
            buildJsonObject {
                put("adapterId", JsonPrimitive(rolled.adapterId))
                put("activeVersion", JsonPrimitive(rolled.version))
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "teach.compile_skill",
                version = "1.0.0",
                description = "Compile recorded demonstration steps into a parameterized FlowCode skill (sensitive steps excluded)",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "skillId": { "type": "string", "minLength": 1 },
                        "name": { "type": "string", "minLength": 1 },
                        "steps": { "type": "array" }
                      },
                      "required": ["skillId", "name", "steps"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("builder.write"),
                risk = RiskTier.REVERSIBLE_WRITE,
                tags = setOf("teach", "flowcode")
            )
        ) { req ->
            val skillId = req.arguments["skillId"]!!.jsonPrimitive.content
            val name = req.arguments["name"]!!.jsonPrimitive.content
            val steps = req.arguments["steps"]!!.jsonArray.map {
                json.decodeFromString(RecordedUiStep.serializer(), it.toString())
            }
            val compiled = FlowTeachCompiler.compileToFlowCode(skillId, name, steps)
            buildJsonObject {
                put("skillId", JsonPrimitive(compiled.skillId))
                put("excludedSensitiveSteps", JsonPrimitive(compiled.excludedSensitiveSteps))
                put("flowCodeJs", JsonPrimitive(compiled.flowCodeJs))
            }
        }

        broker.registerTool(
            ToolManifest(
                id = "sdk.scaffold_manifest",
                version = "1.0.0",
                description = "Generate an Agent-Native App SDK manifest with typed action contracts",
                inputSchema = schema(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "appId": { "type": "string", "minLength": 1 },
                        "name": { "type": "string", "minLength": 1 }
                      },
                      "required": ["appId", "name"],
                      "additionalProperties": false
                    }
                    """.trimIndent()
                ),
                requiredCapabilities = setOf("builder.read"),
                risk = RiskTier.READ_ONLY,
                tags = setOf("sdk", "agent-native")
            )
        ) { req ->
            val appId = req.arguments["appId"]!!.jsonPrimitive.content
            val name = req.arguments["name"]!!.jsonPrimitive.content
            val manifest = AgentNativeAppSdk.scaffoldManifest(
                appId = appId,
                name = name,
                actions = listOf(
                    AgentNativeActionContract("items.list", "List items", RiskTier.READ_ONLY, true),
                    AgentNativeActionContract("items.create", "Create item", RiskTier.REVERSIBLE_WRITE, false)
                )
            )
            buildJsonObject {
                put("manifestJson", JsonPrimitive(json.encodeToString(manifest)))
            }
        }
    }
}
