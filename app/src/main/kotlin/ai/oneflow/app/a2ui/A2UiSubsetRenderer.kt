package ai.oneflow.app.a2ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.oneflow.app.theme.OneflowColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validated subset parser and Compose renderer inspired by A2UI v0.9/v1.0.
 * Enforces:
 * - component catalog allowlist (Text, Column, Row, Card, Divider, StatusBadge)
 * - maximum tree depth & node count limits
 * - explicit rejection of unsupported/spoofed components (never renders approval/OS consent UI)
 */
sealed interface A2UiNode {
    data class TextNode(val text: String, val style: String = "body") : A2UiNode
    data class StatusBadge(val label: String, val tone: String = "neutral") : A2UiNode
    data class DividerNode(val id: String) : A2UiNode
    data class ColumnNode(val children: List<A2UiNode>) : A2UiNode
    data class RowNode(val children: List<A2UiNode>) : A2UiNode
    data class CardNode(val title: String?, val child: A2UiNode) : A2UiNode
}

sealed interface A2UiParseResult {
    data class Valid(val surfaceId: String, val version: String, val root: A2UiNode) : A2UiParseResult
    data class Rejected(val reason: String) : A2UiParseResult
}

object A2UiValidator {
    private const val MAX_NODES = 64
    private const val MAX_DEPTH = 8
    private val SUPPORTED_VERSIONS = setOf("0.9", "0.9.1", "1.0")
    private val ALLOWED_TYPES = setOf("Text", "StatusBadge", "Divider", "Column", "Row", "Card")

    fun parseSurface(rawJson: String, json: Json = Json { ignoreUnknownKeys = true }): A2UiParseResult {
        val obj = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (e: Exception) {
            return A2UiParseResult.Rejected("Malformed A2UI JSON: ${e.message}")
        }

        val version = obj["version"]?.jsonPrimitive?.contentOrNull
            ?: return A2UiParseResult.Rejected("Missing A2UI 'version'")
        if (version !in SUPPORTED_VERSIONS) {
            return A2UiParseResult.Rejected("Unsupported A2UI version '$version'")
        }

        val surfaceId = obj["surfaceId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return A2UiParseResult.Rejected("Missing surfaceId")

        val componentsArray = obj["components"] as? JsonArray
            ?: return A2UiParseResult.Rejected("Missing 'components' array")
        if (componentsArray.isEmpty() || componentsArray.size > MAX_NODES) {
            return A2UiParseResult.Rejected("Component count ${componentsArray.size} outside bounds 1..$MAX_NODES")
        }

        val byId = linkedMapOf<String, JsonObject>()
        for (elem in componentsArray) {
            val comp = elem as? JsonObject
                ?: return A2UiParseResult.Rejected("Component entry must be an object")
            val id = comp["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return A2UiParseResult.Rejected("Component missing 'id'")
            if (byId.containsKey(id)) {
                return A2UiParseResult.Rejected("Duplicate component id '$id'")
            }
            byId[id] = comp
        }

        val rootId = obj["rootId"]?.jsonPrimitive?.contentOrNull ?: "root"
        if (!byId.containsKey(rootId)) {
            return A2UiParseResult.Rejected("Missing root component '$rootId'")
        }

        val visiting = mutableSetOf<String>()
        return try {
            val root = buildNode(rootId, byId, visiting, depth = 0)
            A2UiParseResult.Valid(surfaceId, version, root)
        } catch (e: IllegalArgumentException) {
            A2UiParseResult.Rejected(e.message ?: "Validation rejected")
        }
    }

    private fun buildNode(
        id: String,
        byId: Map<String, JsonObject>,
        visiting: MutableSet<String>,
        depth: Int
    ): A2UiNode {
        if (depth > MAX_DEPTH) {
            throw IllegalArgumentException("A2UI tree exceeds max depth $MAX_DEPTH")
        }
        if (!visiting.add(id)) {
            throw IllegalArgumentException("Cycle detected at component '$id'")
        }
        val comp = byId[id] ?: throw IllegalArgumentException("Unknown child ref '$id'")
        val type = comp["component"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("Component '$id' missing 'component' type")

        if (type !in ALLOWED_TYPES) {
            throw IllegalArgumentException("Disallowed or unsupported A2UI component '$type'")
        }

        val node = when (type) {
            "Text" -> A2UiNode.TextNode(
                text = comp["text"]?.jsonPrimitive?.contentOrNull.orEmpty().take(500),
                style = comp["style"]?.jsonPrimitive?.contentOrNull ?: "body"
            )
            "StatusBadge" -> A2UiNode.StatusBadge(
                label = comp["label"]?.jsonPrimitive?.contentOrNull.orEmpty().take(48),
                tone = comp["tone"]?.jsonPrimitive?.contentOrNull ?: "neutral"
            )
            "Divider" -> A2UiNode.DividerNode(id)
            "Column" -> {
                val childIds = comp["children"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                A2UiNode.ColumnNode(childIds.map { buildNode(it, byId, visiting, depth + 1) })
            }
            "Row" -> {
                val childIds = comp["children"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                A2UiNode.RowNode(childIds.map { buildNode(it, byId, visiting, depth + 1) })
            }
            "Card" -> {
                val childId = comp["child"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Card '$id' missing 'child'")
                val title = comp["title"]?.jsonPrimitive?.contentOrNull?.take(80)
                A2UiNode.CardNode(title, buildNode(childId, byId, visiting, depth + 1))
            }
            else -> throw IllegalArgumentException("Unsupported type '$type'")
        }
        visiting.remove(id)
        return node
    }
}

@Composable
fun A2UiSubsetView(result: A2UiParseResult, modifier: Modifier = Modifier) {
    when (result) {
        is A2UiParseResult.Rejected -> {
            Surface(
                modifier = modifier.fillMaxWidth(),
                color = OneflowColors.Surface,
                border = BorderStroke(1.dp, OneflowColors.Error),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "A2UI Rejected: ${result.reason}",
                    color = OneflowColors.Error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        is A2UiParseResult.Valid -> {
            RenderA2UiNode(result.root, modifier)
        }
    }
}

@Composable
private fun RenderA2UiNode(node: A2UiNode, modifier: Modifier = Modifier) {
    when (node) {
        is A2UiNode.TextNode -> {
            val textStyle = when (node.style) {
                "title" -> MaterialTheme.typography.titleMedium
                "caption" -> MaterialTheme.typography.bodySmall
                else -> MaterialTheme.typography.bodyMedium
            }
            val color = if (node.style == "caption") OneflowColors.TextSecondary else OneflowColors.TextPrimary
            Text(text = node.text, style = textStyle, color = color, modifier = modifier)
        }
        is A2UiNode.StatusBadge -> {
            val tint = when (node.tone) {
                "success" -> OneflowColors.Success
                "warning" -> OneflowColors.Warning
                "error" -> OneflowColors.Error
                else -> OneflowColors.TextSecondary
            }
            Surface(
                color = OneflowColors.SurfaceElevated,
                border = BorderStroke(1.dp, tint.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(6.dp),
                modifier = modifier
            ) {
                Text(
                    text = node.label,
                    color = tint,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        is A2UiNode.DividerNode -> {
            HorizontalDivider(color = OneflowColors.Border, thickness = 1.dp, modifier = modifier)
        }
        is A2UiNode.ColumnNode -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
                node.children.forEach { RenderA2UiNode(it) }
            }
        }
        is A2UiNode.RowNode -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
                node.children.forEach { RenderA2UiNode(it) }
            }
        }
        is A2UiNode.CardNode -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
                border = BorderStroke(1.dp, OneflowColors.Border),
                shape = RoundedCornerShape(12.dp),
                modifier = modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(14.dp)
                ) {
                    if (!node.title.isNullOrBlank()) {
                        Text(
                            text = node.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = OneflowColors.TextPrimary
                        )
                    }
                    RenderA2UiNode(node.child)
                }
            }
        }
    }
}
