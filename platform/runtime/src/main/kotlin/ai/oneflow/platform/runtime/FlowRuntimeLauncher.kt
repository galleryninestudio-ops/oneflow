package ai.oneflow.platform.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.nio.file.Path

@Serializable
data class ProcessSpec(
    val executable: String,
    val args: List<String> = emptyList(),
    val workingDir: String,
    val envAllowlist: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 30_000L,
    val maxOutputBytes: Int = 64 * 1024
)

@Serializable
data class ProcessOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val timedOut: Boolean = false,
    val durationMs: Long = 0L
)

/**
 * Structured process launcher (§9.1):
 * - Takes explicit executable + argument vector (never raw shell strings by default)
 * - Clears inherited parent environment, passing only explicitly allowlisted vars
 * - Bounds stdout/stderr memory usage
 * - Enforces wall-clock timeout and coroutine cancellation via destroyForcibly()
 */
class FlowRuntimeLauncher {

    suspend fun run(spec: ProcessSpec): ProcessOutcome = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val cmd = listOf(spec.executable) + spec.args
        val pb = ProcessBuilder(cmd)
        pb.directory(Path.of(spec.workingDir).toFile())
        pb.environment().clear()
        pb.environment().putAll(spec.envAllowlist)

        val process = pb.start()
        try {
            val stdoutDeferred = async { readBounded(process.inputStream, spec.maxOutputBytes) }
            val stderrDeferred = async { readBounded(process.errorStream, spec.maxOutputBytes) }

            val exitCode = withTimeout(spec.timeoutMs) {
                runInterruptible { process.waitFor() }
            }
            val (outText, outTrunc) = stdoutDeferred.await()
            val (errText, errTrunc) = stderrDeferred.await()
            ProcessOutcome(
                exitCode = exitCode,
                stdout = outText,
                stderr = errText,
                stdoutTruncated = outTrunc,
                stderrTruncated = errTrunc,
                timedOut = false,
                durationMs = (System.currentTimeMillis() - start).coerceAtLeast(0L)
            )
        } catch (_: TimeoutCancellationException) {
            process.destroyForcibly()
            ProcessOutcome(
                exitCode = -1,
                stdout = "",
                stderr = "Process timed out after ${spec.timeoutMs}ms",
                timedOut = true,
                durationMs = (System.currentTimeMillis() - start).coerceAtLeast(0L)
            )
        } catch (ce: CancellationException) {
            process.destroyForcibly()
            throw ce
        } finally {
            runCatching { process.destroyForcibly() }
        }
    }

    private fun readBounded(stream: InputStream, limit: Int): Pair<String, Boolean> {
        val buf = ByteArray(4096)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        var truncated = false
        stream.use { s ->
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                val canKeep = (limit - total).coerceAtLeast(0)
                if (canKeep > 0) {
                    val toWrite = n.coerceAtMost(canKeep)
                    out.write(buf, 0, toWrite)
                    total += toWrite
                }
                if (n > canKeep) {
                    truncated = true
                }
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8) to truncated
    }
}
