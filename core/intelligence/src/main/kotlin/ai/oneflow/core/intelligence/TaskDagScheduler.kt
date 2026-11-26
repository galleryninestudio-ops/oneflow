package ai.oneflow.core.intelligence

import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.FlowError
import ai.oneflow.core.contracts.TaskId
import kotlinx.serialization.Serializable

@Serializable
enum class TaskNodeStatus {
    PENDING,
    READY,
    RUNNING,
    COMPLETED,
    FAILED,
    BLOCKED
}

@Serializable
data class TaskNode(
    val id: TaskId,
    val title: String,
    val dependencies: Set<TaskId> = emptySet(),
    val status: TaskNodeStatus = TaskNodeStatus.PENDING,
    val outputSummary: String? = null
)

/**
 * Deterministic task DAG scheduler with cycle & missing-dependency validation (§4.3, Phase 3).
 */
class TaskDag(initialNodes: List<TaskNode>) {
    private val nodes = linkedMapOf<TaskId, TaskNode>()

    init {
        for (n in initialNodes) {
            require(!nodes.containsKey(n.id)) { "Duplicate task id: ${n.id.value}" }
            nodes[n.id] = n
        }
        val err = validate()
        if (err != null) throw IllegalArgumentException(err.message)
    }

    fun validate(): FlowError? {
        for (n in nodes.values) {
            for (dep in n.dependencies) {
                if (!nodes.containsKey(dep)) {
                    return FlowError(ErrorCode.INVALID_ARGUMENT, "Task '${n.id.value}' depends on missing task '${dep.value}'")
                }
            }
        }
        val color = mutableMapOf<TaskId, Int>() // 0=white, 1=gray, 2=black
        fun dfs(id: TaskId): Boolean {
            color[id] = 1
            val deps = nodes[id]?.dependencies.orEmpty()
            for (d in deps) {
                when (color[d] ?: 0) {
                    1 -> return true
                    0 -> if (dfs(d)) return true
                }
            }
            color[id] = 2
            return false
        }
        for (id in nodes.keys) {
            if ((color[id] ?: 0) == 0 && dfs(id)) {
                return FlowError(ErrorCode.INVALID_ARGUMENT, "Cycle detected in task DAG involving '${id.value}'")
            }
        }
        return null
    }

    fun readyNodes(): List<TaskNode> {
        return nodes.values.filter { node ->
            (node.status == TaskNodeStatus.PENDING || node.status == TaskNodeStatus.READY) &&
                node.dependencies.all { dep -> nodes[dep]?.status == TaskNodeStatus.COMPLETED }
        }
    }

    fun markRunning(id: TaskId): TaskNode {
        val n = nodes[id] ?: error("Unknown task ${id.value}")
        val updated = n.copy(status = TaskNodeStatus.RUNNING)
        nodes[id] = updated
        return updated
    }

    fun markCompleted(id: TaskId, summary: String? = null): TaskNode {
        val n = nodes[id] ?: error("Unknown task ${id.value}")
        val updated = n.copy(status = TaskNodeStatus.COMPLETED, outputSummary = summary)
        nodes[id] = updated
        return updated
    }

    fun markFailed(id: TaskId, reason: String): TaskNode {
        val n = nodes[id] ?: error("Unknown task ${id.value}")
        val updated = n.copy(status = TaskNodeStatus.FAILED, outputSummary = reason)
        nodes[id] = updated
        // Mark downstream dependents as BLOCKED
        for ((k, v) in nodes.entries) {
            if (id in v.dependencies && v.status == TaskNodeStatus.PENDING) {
                nodes[k] = v.copy(status = TaskNodeStatus.BLOCKED, outputSummary = "Blocked by failed dependency ${id.value}")
            }
        }
        return updated
    }

    fun snapshot(): List<TaskNode> = nodes.values.toList()
}
