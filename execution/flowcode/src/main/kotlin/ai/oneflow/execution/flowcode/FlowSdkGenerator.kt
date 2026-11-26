package ai.oneflow.execution.flowcode

import ai.oneflow.core.contracts.ToolDefinition

/**
 * Generates frozen `flow.<namespace>.<op>` JS proxy bindings and compact TypeScript-style declarations (§6.2, §6.4).
 */
object FlowSdkGenerator {

    fun generateRuntimePrelude(toolIds: Collection<String>): String {
        val grouped = linkedMapOf<String, MutableList<Pair<String, String>>>()
        for (id in toolIds.sorted()) {
            val dot = id.indexOf('.')
            if (dot > 0 && dot < id.lastIndex) {
                val ns = id.substring(0, dot)
                val op = id.substring(dot + 1)
                grouped.getOrPut(ns) { mutableListOf() }.add(op to id)
            } else {
                grouped.getOrPut("tools") { mutableListOf() }.add(id to id)
            }
        }

        return buildString {
            appendLine("'use strict';")
            appendLine("const __flowRoot = {};")
            for ((ns, ops) in grouped) {
                appendLine("const __ns_$ns = {};")
                for ((op, fullId) in ops) {
                    appendLine(
                        "__ns_$ns[${jsStr(op)}] = function(args) { return __hostCall(${jsStr(fullId)}, JSON.stringify(args || {})); };"
                    )
                }
                appendLine("Object.freeze(__ns_$ns);")
                appendLine("__flowRoot[${jsStr(ns)}] = __ns_$ns;")
            }
            appendLine("Object.freeze(__flowRoot);")
            appendLine("globalThis.flow = __flowRoot;")
        }
    }

    fun generateCompactDeclarations(tools: List<ToolDefinition>): String {
        if (tools.isEmpty()) return "// No matching tools"
        return buildString {
            appendLine("declare const flow: {")
            val byNs = tools.groupBy { t ->
                val idx = t.id.indexOf('.')
                if (idx > 0) t.id.substring(0, idx) else "tools"
            }
            for ((ns, list) in byNs) {
                appendLine("  $ns: {")
                for (t in list) {
                    val op = t.id.substringAfter('.', t.id)
                    appendLine("    /** ${t.description} [risk=${t.risk}, caps=${t.requiredCapabilities.joinToString(",")}] */")
                    appendLine("    $op(args: ${t.inputSchema}): Promise<any>;")
                }
                appendLine("  };")
            }
            appendLine("};")
        }
    }

    private fun jsStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
