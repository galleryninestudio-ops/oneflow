package ai.oneflow.capabilities.development

import ai.oneflow.capabilities.files.ArtifactStore
import ai.oneflow.capabilities.files.TransactionJournal
import ai.oneflow.capabilities.files.WorkspaceFileTools
import ai.oneflow.capabilities.files.WorkspacePathGuard
import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.ToolRequest
import ai.oneflow.core.contracts.ToolRequestId
import ai.oneflow.core.contracts.ToolResultStatus
import ai.oneflow.execution.broker.DefaultToolRegistry
import ai.oneflow.execution.broker.SafeToolBroker
import ai.oneflow.platform.runtime.FlowPkgManifest
import ai.oneflow.platform.runtime.FlowPkgManager
import ai.oneflow.platform.runtime.FlowRuntimeLauncher
import ai.oneflow.platform.runtime.PackageRuntimeKind
import ai.oneflow.platform.runtime.PkgResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator

class Phase4DevelopmentTest {

    @Test
    fun `flowpkg verifies ecdsa signature, rejects tampered payload, and rolls back version`(@TempDir tempDir: Path) {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val keyPair = kpg.generateKeyPair()
        val pkgMgr = FlowPkgManager(tempDir.resolve("pkgs"), keyPair.public)

        val payloadV1 = "echo-v1-binary-fixture".toByteArray()
        val m1 = FlowPkgManager.signManifest(
            FlowPkgManifest(
                id = "foundation-echo",
                version = "1.0.0",
                abi = "arm64-v8a",
                runtime = PackageRuntimeKind.BIONIC,
                sha256Hex = FlowPkgManager.sha256Hex(payloadV1),
                signatureBase64 = "",
                license = "MIT",
                installedSizeBytes = payloadV1.size.toLong()
            ),
            keyPair.private
        )
        assertTrue(pkgMgr.verifyAndInstall(m1, payloadV1) is PkgResult.Ok)

        // Tampered payload must be rejected
        val tampered = "evil-tampered-bytes".toByteArray()
        val badRes = pkgMgr.verifyAndInstall(m1.copy(version = "1.0.1"), tampered)
        assertTrue(badRes is PkgResult.Err)
        assertEquals(ErrorCode.DENIED, (badRes as PkgResult.Err).error.code)

        // Valid v2 install + rollback to v1
        val payloadV2 = "echo-v2-binary-fixture".toByteArray()
        val m2 = FlowPkgManager.signManifest(
            m1.copy(version = "2.0.0", sha256Hex = FlowPkgManager.sha256Hex(payloadV2), installedSizeBytes = payloadV2.size.toLong()),
            keyPair.private
        )
        assertTrue(pkgMgr.verifyAndInstall(m2, payloadV2) is PkgResult.Ok)
        assertEquals("2.0.0", pkgMgr.currentActive("foundation-echo")?.manifest?.version)

        val rolled = pkgMgr.rollback("foundation-echo")
        assertTrue(rolled is PkgResult.Ok)
        assertEquals("1.0.0", pkgMgr.currentActive("foundation-echo")?.manifest?.version)
    }

    @Test
    fun `agent edits fixture python project, runs approved test, handles failure, and rejects unauthorized shell`(@TempDir tempDir: Path) = runBlocking {
        val ws = tempDir.resolve("py_fixture").also { Files.createDirectories(it) }
        Files.writeString(
            ws.resolve("pyproject.toml"),
            "[project]\nname = \"fixture\"\n"
        )
        Files.writeString(
            ws.resolve("calc.py"),
            "def add(a, b):\n    return a + b\n"
        )
        Files.writeString(
            ws.resolve("test_calc.py"),
            """
            import unittest
            from calc import add
            class T(unittest.TestCase):
                def test_add(self):
                    self.assertEqual(add(2, 3), 5)
            if __name__ == '__main__':
                unittest.main()
            """.trimIndent()
        )

        val guard = WorkspacePathGuard(ws)
        val artifacts = ArtifactStore(ws.resolve(".oneflow/artifacts"))
        val journal = TransactionJournal(ws.resolve(".oneflow/journal"), guard)
        val registry = DefaultToolRegistry()
        val broker = SafeToolBroker(registry)
        WorkspaceFileTools(guard, artifacts, journal).registerAll(broker)
        DevWorkspaceTools(
            pathGuard = guard,
            launcher = FlowRuntimeLauncher(),
            approvedExecutables = setOf("python3")
        ).registerAll(broker)

        val grants = setOf("workspace.read", "workspace.write", "runtime.exec")
        val approvals = setOf("process.run_approved@1.0.0")

        // 1. env.detect identifies python project
        val envRes = broker.invoke(
            ToolRequest(ToolRequestId("e1"), "k1", MissionId("m1"), toolId = "env.detect", toolVersion = "1.0.0", arguments = buildJsonObject {}),
            missionGrants = grants
        )
        assertEquals(ToolResultStatus.OK, envRes.status)
        assertTrue(envRes.data!!["projectTypes"]!!.jsonArray.any { it.jsonPrimitive.content == "python" })

        // 2. code.symbols extracts `add`
        val symRes = broker.invoke(
            ToolRequest(ToolRequestId("s1"), "ks1", MissionId("m1"), toolId = "code.symbols", toolVersion = "1.0.0", arguments = buildJsonObject { put("path", JsonPrimitive("calc.py")) }),
            missionGrants = grants
        )
        assertEquals(ToolResultStatus.OK, symRes.status)
        assertTrue(symRes.data!!["symbols"]!!.toString().contains("add"))

        // 3. Run approved python3 unittest -> exitCode 0
        val testOk = broker.invoke(
            ToolRequest(
                ToolRequestId("p1"), "kp1", MissionId("m1"),
                toolId = "process.run_approved", toolVersion = "1.0.0",
                arguments = buildJsonObject {
                    put("executable", JsonPrimitive("python3"))
                    put("args", buildJsonArray { add(JsonPrimitive("test_calc.py")) })
                }
            ),
            missionGrants = grants,
            sessionApprovals = approvals
        )
        assertEquals(ToolResultStatus.OK, testOk.status)
        assertEquals(0, testOk.data!!["exitCode"]!!.jsonPrimitive.int, "stderr=${testOk.data!!["stderr"]}")

        // 4. Unauthorized shell `sh` must be DENIED
        val shellDenied = broker.invoke(
            ToolRequest(
                ToolRequestId("p2"), "kp2", MissionId("m1"),
                toolId = "process.run_approved", toolVersion = "1.0.0",
                arguments = buildJsonObject {
                    put("executable", JsonPrimitive("sh"))
                    put("args", buildJsonArray { add(JsonPrimitive("-c")); add(JsonPrimitive("id")) })
                }
            ),
            missionGrants = grants,
            sessionApprovals = approvals
        )
        assertEquals(ToolResultStatus.DENIED, shellDenied.status)

        // 5. Intentional bug -> non-zero exit code captured cleanly
        broker.invoke(
            ToolRequest(
                ToolRequestId("w1"), "kw1", MissionId("m1"),
                toolId = "fs.write", toolVersion = "1.0.0",
                arguments = buildJsonObject {
                    put("path", JsonPrimitive("calc.py"))
                    put("content", JsonPrimitive("def add(a, b):\n    return a - b\n"))
                }
            ),
            missionGrants = grants
        )
        val testFail = broker.invoke(
            ToolRequest(
                ToolRequestId("p3"), "kp3", MissionId("m1"),
                toolId = "process.run_approved", toolVersion = "1.0.0",
                arguments = buildJsonObject {
                    put("executable", JsonPrimitive("python3"))
                    put("args", buildJsonArray { add(JsonPrimitive("test_calc.py")) })
                }
            ),
            missionGrants = grants,
            sessionApprovals = approvals
        )
        assertEquals(ToolResultStatus.OK, testFail.status)
        assertNotEquals(0, testFail.data!!["exitCode"]!!.jsonPrimitive.int)
    }
}
