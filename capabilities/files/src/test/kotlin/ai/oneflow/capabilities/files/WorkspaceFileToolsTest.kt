package ai.oneflow.capabilities.files

import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.ToolRequest
import ai.oneflow.core.contracts.ToolRequestId
import ai.oneflow.core.contracts.ToolResultStatus
import ai.oneflow.execution.broker.DefaultToolRegistry
import ai.oneflow.execution.broker.SafeToolBroker
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceFileToolsTest {

    @Test
    fun `path guard rejects traversal and symlink escape while allowing contained writes, patch, verify, and undo`(@TempDir tempDir: Path) = runTest {
        val workspace = tempDir.resolve("ws").also { Files.createDirectories(it) }
        val outside = tempDir.resolve("outside.txt").also { Files.writeString(it, "TOP_SECRET") }
        Files.createSymbolicLink(workspace.resolve("escape_link"), outside)

        val guard = WorkspacePathGuard(workspace)
        val artifacts = ArtifactStore(workspace.resolve(".oneflow/artifacts"))
        val journal = TransactionJournal(workspace.resolve(".oneflow/journal"), guard)
        val registry = DefaultToolRegistry()
        val broker = SafeToolBroker(registry)
        WorkspaceFileTools(guard, artifacts, journal).registerAll(broker)

        val grants = setOf("workspace.read", "workspace.write")

        // Traversal attempt denied
        val traversal = broker.invoke(
            ToolRequest(
                ToolRequestId("r1"), "k1", MissionId("m1"),
                toolId = "fs.read", toolVersion = "1.0.0",
                arguments = buildJsonObject { put("path", JsonPrimitive("../outside.txt")) }
            ),
            missionGrants = grants
        )
        assertEquals(ToolResultStatus.DENIED, traversal.status)

        // Symlink escape attempt denied
        val symlinkRead = broker.invoke(
            ToolRequest(
                ToolRequestId("r2"), "k2", MissionId("m1"),
                toolId = "fs.read", toolVersion = "1.0.0",
                arguments = buildJsonObject { put("path", JsonPrimitive("escape_link")) }
            ),
            missionGrants = grants
        )
        assertEquals(ToolResultStatus.DENIED, symlinkRead.status)

        // Write index.html
        val writeRes = broker.invoke(
            ToolRequest(
                ToolRequestId("r3"), "k3", MissionId("m1"),
                toolId = "fs.write", toolVersion = "1.0.0",
                arguments = buildJsonObject {
                    put("path", JsonPrimitive("index.html"))
                    put("content", JsonPrimitive("<html><head><meta name=\"viewport\"></head><body>Hello</body></html>"))
                }
            ),
            missionGrants = grants
        )
        assertEquals(ToolResultStatus.OK, writeRes.status)
        val sha1 = writeRes.data!!["sha256"]!!.jsonPrimitive.content
        val tx1 = writeRes.data!!["transactionId"]!!.jsonPrimitive.content

        // Stale sha256 rejected on patch
        val stalePatch = broker.invoke(
            ToolRequest(
                ToolRequestId("r4"), "k4", MissionId("m1"),
                toolId = "fs.patch", toolVersion = "1.0.0",
                arguments = buildJsonObject {
                    put("path", JsonPrimitive("index.html"))
                    put("find", JsonPrimitive("Hello"))
                    put("replace", JsonPrimitive("Oneflow"))
                    put("expected_sha256", JsonPrimitive("0000"))
                }
            ),
            missionGrants = grants
        )
        assertEquals(ToolResultStatus.STALE_STATE, stalePatch.status)

        // Valid patch succeeds
        val goodPatch = broker.invoke(
            ToolRequest(
                ToolRequestId("r5"), "k5", MissionId("m1"),
                toolId = "fs.patch", toolVersion = "1.0.0",
                arguments = buildJsonObject {
                    put("path", JsonPrimitive("index.html"))
                    put("find", JsonPrimitive("Hello"))
                    put("replace", JsonPrimitive("Oneflow"))
                    put("expected_sha256", JsonPrimitive(sha1))
                }
            ),
            missionGrants = grants
        )
        assertEquals(ToolResultStatus.OK, goodPatch.status)
        val tx2 = goodPatch.data!!["transactionId"]!!.jsonPrimitive.content

        // Deterministic verify.file passes
        val verifyRes = broker.invoke(
            ToolRequest(
                ToolRequestId("r6"), "k6", MissionId("m1"),
                toolId = "verify.file", toolVersion = "1.0.0",
                arguments = buildJsonObject {
                    put("path", JsonPrimitive("index.html"))
                    put("must_contain", buildJsonArray {
                        add(JsonPrimitive("viewport"))
                        add(JsonPrimitive("Oneflow"))
                    })
                }
            ),
            missionGrants = grants
        )
        assertEquals(ToolResultStatus.OK, verifyRes.status)
        assertTrue(verifyRes.data!!["passed"]!!.jsonPrimitive.boolean)

        // Undo patch restores Hello; undo initial write deletes index.html
        assertTrue(journal.undo(tx2))
        assertTrue(Files.readString(workspace.resolve("index.html")).contains("Hello"))
        assertTrue(journal.undo(tx1))
        assertFalse(Files.exists(workspace.resolve("index.html")))
    }
}
