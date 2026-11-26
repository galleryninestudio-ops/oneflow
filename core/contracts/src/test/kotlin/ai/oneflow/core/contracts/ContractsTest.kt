package ai.oneflow.core.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ContractsTest {

    @Test
    fun `mission transitions forbid direct completion from running`() {
        assertFalse(MissionTransitions.canTransition(MissionState.RUNNING, MissionState.COMPLETED))
        assertTrue(MissionTransitions.canTransition(MissionState.RUNNING, MissionState.VERIFYING))
        assertTrue(MissionTransitions.canTransition(MissionState.VERIFYING, MissionState.COMPLETED))
        assertTrue(MissionTransitions.canTransition(MissionState.VERIFYING, MissionState.RUNNING))
        assertTrue(MissionTransitions.allowedTargets(MissionState.COMPLETED).isEmpty())
        assertTrue(MissionTransitions.allowedTargets(MissionState.FAILED).isEmpty())
        assertTrue(MissionTransitions.allowedTargets(MissionState.CANCELLED).isEmpty())
    }

    @Test
    fun `json schema validator catches missing required, additional properties, and bounds`() {
        val schema = Json.parseToJsonElement(
            """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "minLength": 2 },
                "limit": { "type": "integer", "minimum": 1, "maximum": 20 }
              },
              "required": ["query"],
              "additionalProperties": false
            }
            """.trimIndent()
        ).jsonObject

        val valid = Json.parseToJsonElement("""{"query":"kotlin","limit":5}""")
        assertTrue(JsonSchemaValidator.validate(schema, valid).isEmpty())

        val invalid = Json.parseToJsonElement("""{"query":"a","limit":99,"extra":true}""")
        val violations = JsonSchemaValidator.validate(schema, invalid)
        assertEquals(3, violations.size)

        val missingReq = Json.parseToJsonElement("""{"limit":5}""")
        val missingViolations = JsonSchemaValidator.validate(schema, missingReq)
        assertEquals(1, missingViolations.size)
        assertTrue(missingViolations[0].path.endsWith(".query"))
    }

    @Test
    fun `secret redactor scrubs bearer and sk tokens`() {
        val raw = "Authorization: Bearer sk-abc1234567890xyz and api_key=my_secret_value_123"
        val redacted = SecretRedactor.redact(raw, listOf("my_secret_value_123"))
        assertFalse(redacted.contains("sk-abc1234567890xyz"))
        assertFalse(redacted.contains("my_secret_value_123"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `value ids reject blank values`() {
        assertThrows<IllegalArgumentException> { MissionId("   ") }
        assertThrows<IllegalArgumentException> { ToolRequestId("") }
    }
}
