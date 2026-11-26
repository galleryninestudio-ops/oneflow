package ai.oneflow.capabilities.computeruse

import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

@Serializable
data class UiNodeRef(
    val refId: String,
    val className: String,
    val text: String = "",
    val contentDescription: String = "",
    val viewId: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val isPassword: Boolean = false,
    val isSensitive: Boolean = false,
    val bounds: UiBounds = UiBounds(0, 0, 0, 0)
)

@Serializable
data class UiTreeSnapshot(
    val epoch: Long,
    val packageName: String,
    val windowTitle: String = "",
    val capturedAtMs: Long,
    val nodes: List<UiNodeRef>,
    val truncated: Boolean = false,
    val redactedCount: Int = 0
)

enum class GlobalDeviceAction {
    BACK,
    HOME,
    RECENTS
}

enum class MediaProjectionConsentStatus {
    NOT_REQUESTED,
    GRANTED_ACTIVE,
    STOPPED_REVOKED,
    DENIED
}

@Serializable
data class MediaProjectionState(
    val status: MediaProjectionConsentStatus = MediaProjectionConsentStatus.NOT_REQUESTED,
    val lastUpdatedMs: Long = 0L,
    val note: String = "Screen capture not requested"
)

@Serializable
data class ShizukuBridgeStatus(
    val available: Boolean = false,
    val permissionGranted: Boolean = false,
    val userServiceBound: Boolean = false,
    val uid: Int? = null,
    val note: String = "Shizuku not initialized"
)

data class FlowAccessActionResult(
    val ok: Boolean,
    val message: String,
    val errorCode: String? = null,
    val epochAfter: Long? = null
)

interface FlowAccessBackend {
    suspend fun captureActiveTree(epoch: Long): Result<UiTreeSnapshot>
    suspend fun clickNode(epoch: Long, refId: String): FlowAccessActionResult
    suspend fun inputText(epoch: Long, refId: String, text: String): FlowAccessActionResult
    suspend fun performGlobalAction(action: GlobalDeviceAction): FlowAccessActionResult
}

/**
 * Pure-core FlowAccess interaction router:
 * - Enforces immediate emergency stop kill-switch
 * - Redacts password/sensitive fields and protected system packages
 * - Rejects stale UI element refs when snapshot epoch advances
 * - Tracks MediaProjection consent & Shizuku read-only bridge status
 */
class FlowAccessRouter(
    private val backend: FlowAccessBackend,
    private val protectedPackages: Set<String> = DEFAULT_PROTECTED_PACKAGES
) {
    private val emergencyStopped = AtomicBoolean(false)
    private val epochCounter = AtomicLong(0L)
    @Volatile
    private var latestSnapshot: UiTreeSnapshot? = null

    @Volatile
    private var mediaProjectionState = MediaProjectionState()

    @Volatile
    private var shizukuStatus = ShizukuBridgeStatus()

    fun isEmergencyStopped(): Boolean = emergencyStopped.get()

    fun triggerEmergencyStop(reason: String = "User emergency stop"): Boolean {
        emergencyStopped.set(true)
        revokeMediaProjection("Revoked by emergency stop: $reason")
        return true
    }

    fun clearEmergencyStop() {
        emergencyStopped.set(false)
    }

    fun currentEpoch(): Long = epochCounter.get()

    fun getMediaProjectionState(): MediaProjectionState = mediaProjectionState

    fun updateMediaProjectionGranted(nowMs: Long = System.currentTimeMillis()) {
        mediaProjectionState = MediaProjectionState(
            status = MediaProjectionConsentStatus.GRANTED_ACTIVE,
            lastUpdatedMs = nowMs,
            note = "MediaProjection active with explicit user consent"
        )
    }

    fun revokeMediaProjection(note: String = "MediaProjection stopped by user", nowMs: Long = System.currentTimeMillis()) {
        mediaProjectionState = MediaProjectionState(
            status = MediaProjectionConsentStatus.STOPPED_REVOKED,
            lastUpdatedMs = nowMs,
            note = note
        )
    }

    fun markMediaProjectionDenied(nowMs: Long = System.currentTimeMillis()) {
        mediaProjectionState = MediaProjectionState(
            status = MediaProjectionConsentStatus.DENIED,
            lastUpdatedMs = nowMs,
            note = "User denied MediaProjection consent"
        )
    }

    fun getShizukuStatus(): ShizukuBridgeStatus = shizukuStatus

    fun updateShizukuStatus(status: ShizukuBridgeStatus) {
        shizukuStatus = status
    }

    suspend fun captureSnapshot(): Result<UiTreeSnapshot> {
        val nextEpoch = epochCounter.incrementAndGet()
        val raw = backend.captureActiveTree(nextEpoch).getOrElse { return Result.failure(it) }
        if (raw.packageName in protectedPackages) {
            val redacted = UiTreeSnapshot(
                epoch = nextEpoch,
                packageName = raw.packageName,
                windowTitle = "[PROTECTED_WINDOW]",
                capturedAtMs = raw.capturedAtMs,
                nodes = emptyList(),
                truncated = false,
                redactedCount = raw.nodes.size.coerceAtLeast(1)
            )
            latestSnapshot = redacted
            return Result.success(redacted)
        }
        var redactedCount = raw.redactedCount
        val sanitized = raw.nodes.map { node ->
            if (node.isPassword || node.isSensitive) {
                redactedCount++
                node.copy(text = "[REDACTED]", contentDescription = "[REDACTED]")
            } else {
                node
            }
        }
        val clean = raw.copy(epoch = nextEpoch, nodes = sanitized, redactedCount = redactedCount)
        latestSnapshot = clean
        return Result.success(clean)
    }

    suspend fun tapRef(expectedEpoch: Long, refId: String): FlowAccessActionResult {
        if (emergencyStopped.get()) {
            return FlowAccessActionResult(false, "Blocked: Emergency stop is active", errorCode = "EMERGENCY_STOPPED")
        }
        val snap = latestSnapshot
            ?: return FlowAccessActionResult(false, "No UI snapshot captured yet", errorCode = "STALE_UI_REF")
        if (snap.epoch != expectedEpoch || expectedEpoch != epochCounter.get()) {
            return FlowAccessActionResult(
                false,
                "Stale UI ref epoch=$expectedEpoch (current=${epochCounter.get()})",
                errorCode = "STALE_UI_REF"
            )
        }
        if (snap.packageName in protectedPackages) {
            return FlowAccessActionResult(false, "Blocked: Protected package ${snap.packageName}", errorCode = "PROTECTED_UI")
        }
        val node = snap.nodes.firstOrNull { it.refId == refId }
            ?: return FlowAccessActionResult(false, "Unknown refId '$refId' in epoch $expectedEpoch", errorCode = "NOT_FOUND")
        if (node.isPassword || node.isSensitive) {
            return FlowAccessActionResult(false, "Blocked: Sensitive/password field actuation denied", errorCode = "SENSITIVE_FIELD")
        }
        return backend.clickNode(expectedEpoch, refId)
    }

    suspend fun inputText(expectedEpoch: Long, refId: String, text: String): FlowAccessActionResult {
        if (emergencyStopped.get()) {
            return FlowAccessActionResult(false, "Blocked: Emergency stop is active", errorCode = "EMERGENCY_STOPPED")
        }
        val snap = latestSnapshot
            ?: return FlowAccessActionResult(false, "No UI snapshot captured yet", errorCode = "STALE_UI_REF")
        if (snap.epoch != expectedEpoch || expectedEpoch != epochCounter.get()) {
            return FlowAccessActionResult(
                false,
                "Stale UI ref epoch=$expectedEpoch (current=${epochCounter.get()})",
                errorCode = "STALE_UI_REF"
            )
        }
        if (snap.packageName in protectedPackages) {
            return FlowAccessActionResult(false, "Blocked: Protected package ${snap.packageName}", errorCode = "PROTECTED_UI")
        }
        val node = snap.nodes.firstOrNull { it.refId == refId }
            ?: return FlowAccessActionResult(false, "Unknown refId '$refId' in epoch $expectedEpoch", errorCode = "NOT_FOUND")
        if (node.isPassword || node.isSensitive) {
            return FlowAccessActionResult(false, "Blocked: Cannot input into sensitive/password field", errorCode = "SENSITIVE_FIELD")
        }
        if (!node.editable) {
            return FlowAccessActionResult(false, "Blocked: Target node '$refId' is not editable", errorCode = "NOT_EDITABLE")
        }
        return backend.inputText(expectedEpoch, refId, text)
    }

    suspend fun globalAction(action: GlobalDeviceAction): FlowAccessActionResult {
        if (emergencyStopped.get()) {
            return FlowAccessActionResult(false, "Blocked: Emergency stop is active", errorCode = "EMERGENCY_STOPPED")
        }
        return backend.performGlobalAction(action)
    }

    companion object {
        val DEFAULT_PROTECTED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings.biometrics",
            "com.google.android.gms.auth",
            "com.android.vending"
        )
    }
}
