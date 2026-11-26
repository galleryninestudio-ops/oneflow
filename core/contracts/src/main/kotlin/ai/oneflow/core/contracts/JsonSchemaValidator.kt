package ai.oneflow.core.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SchemaViolation(
    val path: String,
    val message: String
)

/**
 * Pure Kotlin deterministic validator for the JSON Schema subset used by Oneflow tool contracts.
 * Enforces: type, properties, required, additionalProperties, enum, minimum, maximum,
 * minLength, maxLength, items, minItems, maxItems.
 */
object JsonSchemaValidator {
    fun validate(schema: JsonObject, instance: JsonElement, path: String = "$"): List<SchemaViolation> {
        val violations = mutableListOf<SchemaViolation>()
        validateNode(schema, instance, path, violations)
        return violations
    }

    private fun validateNode(
        schema: JsonObject,
        instance: JsonElement,
        path: String,
        violations: MutableList<SchemaViolation>
    ) {
        val expectedType = schema["type"]?.let { (it as? JsonPrimitive)?.content }
        if (expectedType != null && !matchesType(expectedType, instance)) {
            violations += SchemaViolation(path, "Expected type '$expectedType' but found ${describeType(instance)}")
            return
        }

        val enumArr = schema["enum"] as? JsonArray
        if (enumArr != null && enumArr.none { it == instance }) {
            violations += SchemaViolation(path, "Value is not one of allowed enum values")
        }

        when (instance) {
            is JsonObject -> validateObject(schema, instance, path, violations)
            is JsonArray -> validateArray(schema, instance, path, violations)
            is JsonPrimitive -> validatePrimitive(schema, instance, path, violations)
        }
    }

    private fun validateObject(
        schema: JsonObject,
        obj: JsonObject,
        path: String,
        violations: MutableList<SchemaViolation>
    ) {
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        for (req in required) {
            if (!obj.containsKey(req)) {
                violations += SchemaViolation("$path.$req", "Missing required property '$req'")
            }
        }

        val properties = schema["properties"] as? JsonObject
        val additionalAllowed = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull ?: true

        for ((key, value) in obj) {
            val childSchema = properties?.get(key) as? JsonObject
            if (childSchema != null) {
                validateNode(childSchema, value, "$path.$key", violations)
            } else if (!additionalAllowed) {
                violations += SchemaViolation("$path.$key", "Additional property '$key' is not allowed")
            }
        }
    }

    private fun validateArray(
        schema: JsonObject,
        arr: JsonArray,
        path: String,
        violations: MutableList<SchemaViolation>
    ) {
        val minItems = (schema["minItems"] as? JsonPrimitive)?.intOrNull
        if (minItems != null && arr.size < minItems) {
            violations += SchemaViolation(path, "Array has ${arr.size} items, minimum is $minItems")
        }
        val maxItems = (schema["maxItems"] as? JsonPrimitive)?.intOrNull
        if (maxItems != null && arr.size > maxItems) {
            violations += SchemaViolation(path, "Array has ${arr.size} items, maximum is $maxItems")
        }
        val itemSchema = schema["items"] as? JsonObject
        if (itemSchema != null) {
            arr.forEachIndexed { index, element ->
                validateNode(itemSchema, element, "$path[$index]", violations)
            }
        }
    }

    private fun validatePrimitive(
        schema: JsonObject,
        primitive: JsonPrimitive,
        path: String,
        violations: MutableList<SchemaViolation>
    ) {
        if (primitive.isString) {
            val str = primitive.content
            val minLen = (schema["minLength"] as? JsonPrimitive)?.intOrNull
            if (minLen != null && str.length < minLen) {
                violations += SchemaViolation(path, "String length ${str.length} < minLength $minLen")
            }
            val maxLen = (schema["maxLength"] as? JsonPrimitive)?.intOrNull
            if (maxLen != null && str.length > maxLen) {
                violations += SchemaViolation(path, "String length ${str.length} > maxLength $maxLen")
            }
        } else if (primitive !is JsonNull) {
            val num = primitive.doubleOrNull
            if (num != null) {
                val min = (schema["minimum"] as? JsonPrimitive)?.doubleOrNull
                if (min != null && num < min) {
                    violations += SchemaViolation(path, "Number $num < minimum $min")
                }
                val max = (schema["maximum"] as? JsonPrimitive)?.doubleOrNull
                if (max != null && num > max) {
                    violations += SchemaViolation(path, "Number $num > maximum $max")
                }
            }
        }
    }

    private fun matchesType(expected: String, element: JsonElement): Boolean = when (expected) {
        "object" -> element is JsonObject
        "array" -> element is JsonArray
        "string" -> element is JsonPrimitive && element.isString
        "boolean" -> element is JsonPrimitive && !element.isString && element.booleanOrNull != null
        "integer" -> element is JsonPrimitive && !element.isString && isIntegral(element)
        "number" -> element is JsonPrimitive && !element.isString && element.doubleOrNull != null
        "null" -> element is JsonNull
        else -> true
    }

    private fun isIntegral(p: JsonPrimitive): Boolean {
        val d = p.doubleOrNull ?: return false
        return d.isFinite() && d % 1.0 == 0.0
    }

    private fun describeType(element: JsonElement): String = when (element) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            element.isString -> "string"
            element.booleanOrNull != null -> "boolean"
            element.doubleOrNull != null -> "number"
            else -> "primitive"
        }
    }
}
