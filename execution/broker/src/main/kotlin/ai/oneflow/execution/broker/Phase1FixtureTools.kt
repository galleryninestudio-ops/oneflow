package ai.oneflow.execution.broker

import ai.oneflow.core.contracts.ModelToolSpec
import ai.oneflow.core.contracts.RiskTier
import ai.oneflow.core.contracts.ToolManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deterministic fixture tools for Phase 1 harness verification.
 * Explicitly labeled as fixture tools — never real device/shell capabilities.
 */
object Phase1FixtureTools {

    fun registerAll(broker: SafeToolBroker): List<ModelToolSpec> {
        val echoManifest = ToolManifest(
            id = "fixture_echo",
            version = "1.0.0",
            description = "Phase 1 deterministic fixture echo tool (returns message unchanged)",
            inputSchema = Json.parseToJsonElement(
                """
                {
                  "type": "object",
                  "properties": { "message": { "type": "string", "minLength": 1 } },
                  "required": ["message"],
                  "additionalProperties": false
                }
                """.trimIndent()
            ).jsonObject,
            requiredCapabilities = setOf("fixture.read"),
            risk = RiskTier.READ_ONLY,
            tags = setOf("fixture", "phase1")
        )

        val addManifest = ToolManifest(
            id = "fixture_math_add",
            version = "1.0.0",
            description = "Phase 1 deterministic fixture arithmetic tool (adds two numbers a + b)",
            inputSchema = Json.parseToJsonElement(
                """
                {
                  "type": "object",
                  "properties": {
                    "a": { "type": "number" },
                    "b": { "type": "number" }
                  },
                  "required": ["a", "b"],
                  "additionalProperties": false
                }
                """.trimIndent()
            ).jsonObject,
            requiredCapabilities = setOf("fixture.read"),
            risk = RiskTier.READ_ONLY,
            tags = setOf("fixture", "math", "phase1")
        )

        val sensitiveManifest = ToolManifest(
            id = "fixture_publish_note",
            version = "1.0.0",
            description = "Phase 1 approval-gated fixture tool simulating an external side effect",
            inputSchema = Json.parseToJsonElement(
                """
                {
                  "type": "object",
                  "properties": { "note": { "type": "string", "minLength": 1 } },
                  "required": ["note"],
                  "additionalProperties": false
                }
                """.trimIndent()
            ).jsonObject,
            requiredCapabilities = setOf("fixture.write"),
            risk = RiskTier.EXTERNAL_SIDE_EFFECT,
            tags = setOf("fixture", "approval", "phase1")
        )

        broker.registerTool(echoManifest) { req ->
            val msg = req.arguments["message"]?.jsonPrimitive?.content.orEmpty()
            buildJsonObject {
                put("echoed", JsonPrimitive(msg))
                put("fixture", JsonPrimitive(true))
            }
        }

        broker.registerTool(addManifest) { req ->
            val a = req.arguments["a"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val b = req.arguments["b"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            buildJsonObject {
                put("sum", JsonPrimitive(a + b))
                put("fixture", JsonPrimitive(true))
            }
        }

        broker.registerTool(sensitiveManifest) { req ->
            val note = req.arguments["note"]?.jsonPrimitive?.content.orEmpty()
            buildJsonObject {
                put("published", JsonPrimitive(true))
                put("note", JsonPrimitive(note))
            }
        }

        return listOf(echoManifest, addManifest, sensitiveManifest).map { m ->
            ModelToolSpec(
                name = m.id,
                description = m.description,
                parametersSchema = m.inputSchema
            )
        }
    }
}
