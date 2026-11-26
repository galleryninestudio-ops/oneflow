package ai.oneflow.app.computeruse

import ai.oneflow.capabilities.computeruse.FlowAccessActionResult
import ai.oneflow.capabilities.computeruse.FlowAccessBackend
import ai.oneflow.capabilities.computeruse.GlobalDeviceAction
import ai.oneflow.capabilities.computeruse.UiBounds
import ai.oneflow.capabilities.computeruse.UiNodeRef
import ai.oneflow.capabilities.computeruse.UiTreeSnapshot
import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

class OneflowAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Active-window snapshots are captured on demand by FlowAccessRouter.
    }

    override fun onInterrupt() {
        // Nothing to interrupt synchronously; emergency stop is enforced in FlowAccessRouter.
    }

    override fun onDestroy() {
        instanceRef = null
        super.onDestroy()
    }

    fun captureSnapshotForEpoch(epoch: Long): Result<UiTreeSnapshot> {
        val root = rootInActiveWindow
            ?: return Result.failure(IllegalStateException("No active window accessible"))
        val pkg = root.packageName?.toString().orEmpty()
        val nodes = mutableListOf<UiNodeRef>()
        val epochRefs = ConcurrentHashMap<String, AccessibilityNodeInfo>()
        var redacted = 0
        var truncated = false
        var counter = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            if (depth > MAX_DEPTH || nodes.size >= MAX_NODES) {
                truncated = true
                return
            }
            val refId = "n${++counter}"
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val isPw = node.isPassword
            if (isPw) redacted++
            val ref = UiNodeRef(
                refId = refId,
                className = node.className?.toString().orEmpty(),
                text = if (isPw) "[REDACTED]" else node.text?.toString().orEmpty(),
                contentDescription = if (isPw) "[REDACTED]" else node.contentDescription?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                clickable = node.isClickable,
                editable = node.isEditable,
                isPassword = isPw,
                isSensitive = isPw,
                bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom)
            )
            nodes.add(ref)
            epochRefs[refId] = AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
        }

        walk(root, 0)
        synchronized(refCacheLock) {
            cachedEpoch = epoch
            cachedNodes.clear()
            cachedNodes.putAll(epochRefs)
        }
        return Result.success(
            UiTreeSnapshot(
                epoch = epoch,
                packageName = pkg,
                windowTitle = pkg,
                capturedAtMs = System.currentTimeMillis(),
                nodes = nodes,
                truncated = truncated,
                redactedCount = redacted
            )
        )
    }

    fun clickRef(epoch: Long, refId: String): FlowAccessActionResult {
        val node = synchronized(refCacheLock) {
            if (cachedEpoch != epoch) null else cachedNodes[refId]
        } ?: return FlowAccessActionResult(false, "Stale or missing AccessibilityNodeInfo for $refId", errorCode = "STALE_UI_REF")

        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return FlowAccessActionResult(ok, if (ok) "Clicked $refId" else "ACTION_CLICK failed for $refId")
    }

    fun setTextRef(epoch: Long, refId: String, text: String): FlowAccessActionResult {
        val node = synchronized(refCacheLock) {
            if (cachedEpoch != epoch) null else cachedNodes[refId]
        } ?: return FlowAccessActionResult(false, "Stale or missing AccessibilityNodeInfo for $refId", errorCode = "STALE_UI_REF")

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return FlowAccessActionResult(ok, if (ok) "Set text on $refId" else "ACTION_SET_TEXT failed for $refId")
    }

    fun runGlobal(action: GlobalDeviceAction): FlowAccessActionResult {
        val code = when (action) {
            GlobalDeviceAction.BACK -> GLOBAL_ACTION_BACK
            GlobalDeviceAction.HOME -> GLOBAL_ACTION_HOME
            GlobalDeviceAction.RECENTS -> GLOBAL_ACTION_RECENTS
        }
        val ok = performGlobalAction(code)
        return FlowAccessActionResult(ok, if (ok) "Performed $action" else "Global action $action failed")
    }

    companion object {
        private const val MAX_DEPTH = 16
        private const val MAX_NODES = 120
        @Volatile
        private var instanceRef: WeakReference<OneflowAccessibilityService>? = null
        private val refCacheLock = Any()
        private var cachedEpoch: Long = -1L
        private val cachedNodes = HashMap<String, AccessibilityNodeInfo>()

        fun isConnected(): Boolean = instanceRef?.get() != null

        val backend: FlowAccessBackend = object : FlowAccessBackend {
            override suspend fun captureActiveTree(epoch: Long): Result<UiTreeSnapshot> {
                val svc = instanceRef?.get()
                    ?: return Result.failure(IllegalStateException("Oneflow Accessibility Service is not enabled"))
                return svc.captureSnapshotForEpoch(epoch)
            }

            override suspend fun clickNode(epoch: Long, refId: String): FlowAccessActionResult {
                val svc = instanceRef?.get()
                    ?: return FlowAccessActionResult(false, "Accessibility Service not enabled", errorCode = "SERVICE_DISABLED")
                return svc.clickRef(epoch, refId)
            }

            override suspend fun inputText(epoch: Long, refId: String, text: String): FlowAccessActionResult {
                val svc = instanceRef?.get()
                    ?: return FlowAccessActionResult(false, "Accessibility Service not enabled", errorCode = "SERVICE_DISABLED")
                return svc.setTextRef(epoch, refId, text)
            }

            override suspend fun performGlobalAction(action: GlobalDeviceAction): FlowAccessActionResult {
                val svc = instanceRef?.get()
                    ?: return FlowAccessActionResult(false, "Accessibility Service not enabled", errorCode = "SERVICE_DISABLED")
                return svc.runGlobal(action)
            }
        }
    }
}
