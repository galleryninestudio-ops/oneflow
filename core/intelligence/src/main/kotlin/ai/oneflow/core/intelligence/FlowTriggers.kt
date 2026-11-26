package ai.oneflow.core.intelligence

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class TriggerSpec(
    val triggerId: String,
    val name: String,
    val intervalMinutes: Long,
    val dedupeWindowMs: Long = 60_000L,
    val paused: Boolean = false
)

@Serializable
data class TriggerRunRecord(
    val triggerId: String,
    val dedupeKey: String,
    val firedAtMs: Long,
    val status: String
)

/**
 * Deferrable workflow trigger registry with dedupe window and pause/resume controls.
 */
class FlowTriggerRegistry {
    private val triggers = ConcurrentHashMap<String, TriggerSpec>()
    private val lastFiredByDedupe = ConcurrentHashMap<String, Long>()
    private val history = mutableListOf<TriggerRunRecord>()

    fun register(spec: TriggerSpec) {
        triggers[spec.triggerId] = spec
    }

    fun setPaused(triggerId: String, paused: Boolean): Boolean {
        val existing = triggers[triggerId] ?: return false
        triggers[triggerId] = existing.copy(paused = paused)
        return true
    }

    fun shouldFire(triggerId: String, dedupeKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val spec = triggers[triggerId] ?: return false
        if (spec.paused) return false
        val composite = "$triggerId::$dedupeKey"
        val last = lastFiredByDedupe[composite]
        if (last != null && (nowMs - last) < spec.dedupeWindowMs) {
            return false
        }
        lastFiredByDedupe[composite] = nowMs
        synchronized(history) {
            history.add(TriggerRunRecord(triggerId, dedupeKey, nowMs, "FIRED"))
        }
        return true
    }

    fun listTriggers(): List<TriggerSpec> = triggers.values.sortedBy { it.triggerId }

    fun getHistory(): List<TriggerRunRecord> = synchronized(history) { history.toList() }
}
