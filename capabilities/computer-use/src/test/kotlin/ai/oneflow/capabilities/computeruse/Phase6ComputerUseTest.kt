package ai.oneflow.capabilities.computeruse

import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.ToolRequest
import ai.oneflow.core.contracts.ToolRequestId
import ai.oneflow.core.contracts.ToolResultStatus
import ai.oneflow.execution.broker.DefaultToolRegistry
import ai.oneflow.execution.broker.SafeToolBroker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeFlowAccessBackend : FlowAccessBackend {
    var currentPackage = "ai.oneflow.fixture"
    var clicks = mutableListOf<Pair<Long, String>>()
    var inputs = mutableListOf<Triple<Long, String, String>>()
    var globals = mutableListOf<GlobalDeviceAction>()

    override suspend fun captureActiveTree(epoch: Long): Result<UiTreeSnapshot> {
        return Result.success(
            UiTreeSnapshot(
                epoch = epoch,
                packageName = currentPackage,
                windowTitle = "Owned Fixture Screen",
                capturedAtMs = 1000L + epoch,
                nodes = listOf(
                    UiNodeRef(refId = "n1", className = "android.widget.Button", text = "Submit", clickable = true),
                    UiNodeRef(refId = "n2", className = "android.widget.EditText", text = "", editable = true),
                    UiNodeRef(refId = "n3", className = "android.widget.EditText", text = "super-secret-pin", editable = true, isPassword = true)
                )
            )
        )
    }

    override suspend fun clickNode(epoch: Long, refId: String): FlowAccessActionResult {
        clicks.add(epoch to refId)
        return FlowAccessActionResult(true, "Clicked $refId at epoch $epoch", epochAfter = epoch)
    }

    override suspend fun inputText(epoch: Long, refId: String, text: String): FlowAccessActionResult {
        inputs.add(Triple(epoch, refId, text))
        return FlowAccessActionResult(true, "Input into $refId at epoch $epoch", epochAfter = epoch)
    }

    override suspend fun performGlobalAction(action: GlobalDeviceAction): FlowAccessActionResult {
        globals.add(action)
        return FlowAccessActionResult(true, "Performed $action")
    }
}

class Phase6ComputerUseTest {

    @Test
    fun `sensitive password nodes and protected packages are redacted and cannot be actuated`() = runBlocking {
        val backend = FakeFlowAccessBackend()
        val router = FlowAccessRouter(backend)

        val snap = router.captureSnapshot().getOrThrow()
        assertEquals(1, snap.redactedCount)
        assertEquals("[REDACTED]", snap.nodes.first { it.refId == "n3" }.text)

        val tapPw = router.tapRef(snap.epoch, "n3")
        assertFalse(tapPw.ok)
        assertEquals("SENSITIVE_FIELD", tapPw.errorCode)

        val inputPw = router.inputText(snap.epoch, "n3", "1234")
        assertFalse(inputPw.ok)
        assertEquals("SENSITIVE_FIELD", inputPw.errorCode)

        backend.currentPackage = "com.android.systemui"
        val protectedSnap = router.captureSnapshot().getOrThrow()
        assertTrue(protectedSnap.nodes.isEmpty())
        assertEquals("[PROTECTED_WINDOW]", protectedSnap.windowTitle)
    }

    @Test
    fun `stale UI refs are rejected after snapshot epoch advances`() = runBlocking {
        val backend = FakeFlowAccessBackend()
        val router = FlowAccessRouter(backend)

        val snap1 = router.captureSnapshot().getOrThrow()
        val snap2 = router.captureSnapshot().getOrThrow()

        val stale = router.tapRef(snap1.epoch, "n1")
        assertFalse(stale.ok)
        assertEquals("STALE_UI_REF", stale.errorCode)

        val fresh = router.tapRef(snap2.epoch, "n1")
        assertTrue(fresh.ok)
        assertEquals(listOf(snap2.epoch to "n1"), backend.clicks)
    }

    @Test
    fun `emergency stop halts all actuation and revokes media projection consent`() = runBlocking {
        val backend = FakeFlowAccessBackend()
        val router = FlowAccessRouter(backend)
        router.updateMediaProjectionGranted(nowMs = 5000L)
        assertEquals(MediaProjectionConsentStatus.GRANTED_ACTIVE, router.getMediaProjectionState().status)

        val snap = router.captureSnapshot().getOrThrow()
        router.triggerEmergencyStop("User tapped panic button")

        assertTrue(router.isEmergencyStopped())
        assertEquals(MediaProjectionConsentStatus.STOPPED_REVOKED, router.getMediaProjectionState().status)

        val blockedTap = router.tapRef(snap.epoch, "n1")
        assertFalse(blockedTap.ok)
        assertEquals("EMERGENCY_STOPPED", blockedTap.errorCode)

        val blockedGlobal = router.globalAction(GlobalDeviceAction.HOME)
        assertFalse(blockedGlobal.ok)
        assertEquals("EMERGENCY_STOPPED", blockedGlobal.errorCode)
    }

    @Test
    fun `broker enforces device actuate capability grant and allows authorized actuation`() = runBlocking {
        val backend = FakeFlowAccessBackend()
        val router = FlowAccessRouter(backend)
        val broker = SafeToolBroker(DefaultToolRegistry())
        ComputerUseTools(router).registerAll(broker)

        val missionId = MissionId("m-p6")
        val snap = router.captureSnapshot().getOrThrow()

        val denied = broker.invoke(
            ToolRequest(
                requestId = ToolRequestId("req-1"),
                idempotencyKey = "k-1",
                missionId = missionId,
                toolId = "device.tap_ref",
                toolVersion = "1.0.0",
                arguments = buildJsonObject {
                    put("epoch", JsonPrimitive(snap.epoch))
                    put("refId", JsonPrimitive("n1"))
                }
            ),
            missionGrants = emptySet()
        )
        assertEquals(ToolResultStatus.DENIED, denied.status)
        assertEquals(ErrorCode.DENIED, denied.error?.code)

        val allowed = broker.invoke(
            ToolRequest(
                requestId = ToolRequestId("req-2"),
                idempotencyKey = "k-2",
                missionId = missionId,
                toolId = "device.tap_ref",
                toolVersion = "1.0.0",
                arguments = buildJsonObject {
                    put("epoch", JsonPrimitive(snap.epoch))
                    put("refId", JsonPrimitive("n1"))
                }
            ),
            missionGrants = setOf("device.actuate"),
            sessionApprovals = setOf("device.tap_ref")
        )
        assertEquals(ToolResultStatus.OK, allowed.status)
        assertEquals(listOf(snap.epoch to "n1"), backend.clicks)
    }
}
