package ai.oneflow.execution.broker

import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.FlowError
import ai.oneflow.core.contracts.JsonSchemaValidator
import ai.oneflow.core.contracts.PolicyDecision
import ai.oneflow.core.contracts.PolicyEngine
import ai.oneflow.core.contracts.RetryDisposition
import ai.oneflow.core.contracts.RiskTier
import ai.oneflow.core.contracts.SecretRedactor
import ai.oneflow.core.contracts.ToolBroker
import ai.oneflow.core.contracts.ToolDefinition
import ai.oneflow.core.contracts.ToolExecutionReceipt
import ai.oneflow.core.contracts.ToolManifest
import ai.oneflow.core.contracts.ToolQuery
import ai.oneflow.core.contracts.ToolRegistry
import ai.oneflow.core.contracts.ToolRequest
import ai.oneflow.core.contracts.ToolResult
import ai.oneflow.core.contracts.ToolResultStatus
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

fun interface ToolHandler {
    suspend fun execute(request: ToolRequest): JsonObject
}

class BrokerToolException(
    val status: ToolResultStatus,
    val errorCode: ErrorCode,
    override val message: String
) : RuntimeException(message)

class DefaultToolRegistry : ToolRegistry {
    private val manifests = ConcurrentHashMap<String, ToolManifest>()
    private val latestByToolId = ConcurrentHashMap<String, ToolManifest>()

    override fun register(manifest: ToolManifest) {
        val key = "${manifest.id}@${manifest.version}"
        manifests[key] = manifest
        latestByToolId[manifest.id] = manifest
    }

    override suspend fun discover(query: ToolQuery): List<ToolDefinition> {
        val terms = query.query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return latestByToolId.values
            .asSequence()
            .filter { m ->
                query.namespace == null || m.id.startsWith("${query.namespace}.")
            }
            .map { m ->
                val haystack = buildString {
                    append(m.id.lowercase()).append(' ')
                    append(m.description.lowercase()).append(' ')
                    append(m.tags.joinToString(" ") { it.lowercase() })
                }
                val score = if (terms.isEmpty()) 1 else terms.count { haystack.contains(it) }
                m to score
            }
            .filter { (_, score) -> terms.isEmpty() || score > 0 }
            .sortedByDescending { it.second }
            .take(query.limit)
            .map { (m, _) ->
                ToolDefinition(
                    id = m.id,
                    version = m.version,
                    description = m.description,
                    inputSchema = m.inputSchema,
                    requiredCapabilities = m.requiredCapabilities,
                    risk = m.risk,
                    available = true,
                    exampleUsage = m.exampleUsage
                )
            }
            .toList()
    }

    override fun get(toolId: String, version: String?): ToolManifest? {
        return if (version != null) {
            manifests["$toolId@$version"]
        } else {
            latestByToolId[toolId]
        }
    }
}

class DefaultDenyPolicyEngine : PolicyEngine {
    override fun evaluate(
        manifest: ToolManifest,
        request: ToolRequest,
        missionGrants: Set<String>,
        sessionApprovals: Set<String>
    ): PolicyDecision {
        val missing = manifest.requiredCapabilities.filterNot { it in missionGrants }.toSet()
        if (missing.isNotEmpty()) {
            return PolicyDecision(
                allowed = false,
                requiresApproval = false,
                reason = "Missing mission capability grants: ${missing.joinToString()}",
                missingCapabilities = missing
            )
        }

        if (request.scope.isNotEmpty() && !missionGrants.containsAll(request.scope)) {
            val missingScope = request.scope.filterNot { it in missionGrants }.toSet()
            return PolicyDecision(
                allowed = false,
                requiresApproval = false,
                reason = "Request scope not authorized: ${missingScope.joinToString()}",
                missingCapabilities = missingScope
            )
        }

        val highRisk = manifest.risk == RiskTier.DISRUPTIVE_LOCAL ||
            manifest.risk == RiskTier.EXTERNAL_SIDE_EFFECT ||
            manifest.risk == RiskTier.SENSITIVE_DATA ||
            manifest.risk == RiskTier.IRREVERSIBLE

        if (highRisk) {
            val approvalToken = "${manifest.id}@${manifest.version}"
            if (approvalToken !in sessionApprovals && manifest.id !in sessionApprovals) {
                return PolicyDecision(
                    allowed = false,
                    requiresApproval = true,
                    reason = "Explicit user approval required for ${manifest.risk} tool '${manifest.id}'"
                )
            }
        }

        return PolicyDecision(allowed = true, reason = "Authorized")
    }
}

class SafeToolBroker(
    private val registry: ToolRegistry,
    private val policyEngine: PolicyEngine = DefaultDenyPolicyEngine(),
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() }
) : ToolBroker {

    private val handlers = ConcurrentHashMap<String, ToolHandler>()
    private val idempotencyMutex = Mutex()
    private val cachedResults = mutableMapOf<String, ToolResult>()
    private val receipts = mutableListOf<ToolExecutionReceipt>()

    fun registerTool(manifest: ToolManifest, handler: ToolHandler) {
        registry.register(manifest)
        handlers["${manifest.id}@${manifest.version}"] = handler
    }

    suspend fun receipts(): List<ToolExecutionReceipt> = idempotencyMutex.withLock {
        receipts.toList()
    }

    override suspend fun invoke(
        request: ToolRequest,
        missionGrants: Set<String>,
        sessionApprovals: Set<String>
    ): ToolResult {
        val idempKey = "${request.missionId.value}:${request.toolId}:${request.idempotencyKey}"
        idempotencyMutex.withLock {
            cachedResults[idempKey]?.let { return it }
        }

        val start = clockEpochMs()
        val manifest = registry.get(request.toolId, request.toolVersion)
            ?: return recordAndReturn(
                idempKey = idempKey,
                request = request,
                cacheResult = false,
                result = ToolResult(
                    requestId = request.requestId,
                    status = ToolResultStatus.UNAVAILABLE,
                    error = FlowError(ErrorCode.UNAVAILABLE, "Tool '${request.toolId}@${request.toolVersion}' is not registered"),
                    durationMs = clockEpochMs() - start
                )
            )

        val violations = JsonSchemaValidator.validate(manifest.inputSchema, request.arguments)
        if (violations.isNotEmpty()) {
            val summary = violations.joinToString("; ") { "${it.path}: ${it.message}" }
            return recordAndReturn(
                idempKey = idempKey,
                request = request,
                cacheResult = false,
                result = ToolResult(
                    requestId = request.requestId,
                    status = ToolResultStatus.INVALID_ARGUMENT,
                    error = FlowError(ErrorCode.INVALID_ARGUMENT, summary),
                    durationMs = clockEpochMs() - start
                )
            )
        }

        val decision = policyEngine.evaluate(manifest, request, missionGrants, sessionApprovals)
        if (!decision.allowed) {
            val status = if (decision.requiresApproval) ToolResultStatus.APPROVAL_REQUIRED else ToolResultStatus.DENIED
            val code = if (decision.requiresApproval) ErrorCode.APPROVAL_REQUIRED else ErrorCode.DENIED
            return recordAndReturn(
                idempKey = idempKey,
                request = request,
                cacheResult = false,
                result = ToolResult(
                    requestId = request.requestId,
                    status = status,
                    error = FlowError(code, decision.reason),
                    durationMs = clockEpochMs() - start
                )
            )
        }

        val handler = handlers["${manifest.id}@${manifest.version}"]
            ?: return recordAndReturn(
                idempKey = idempKey,
                request = request,
                cacheResult = false,
                result = ToolResult(
                    requestId = request.requestId,
                    status = ToolResultStatus.UNAVAILABLE,
                    error = FlowError(ErrorCode.UNAVAILABLE, "No handler bound for '${manifest.id}'"),
                    durationMs = clockEpochMs() - start
                )
            )

        val result = try {
            val data = withTimeout(manifest.execution.timeoutMs) {
                handler.execute(request)
            }
            ToolResult(
                requestId = request.requestId,
                status = ToolResultStatus.OK,
                data = data,
                durationMs = (clockEpochMs() - start).coerceAtLeast(0L)
            )
        } catch (_: TimeoutCancellationException) {
            ToolResult(
                requestId = request.requestId,
                status = ToolResultStatus.TIMEOUT,
                error = FlowError(ErrorCode.TIMEOUT, "Tool '${manifest.id}' timed out after ${manifest.execution.timeoutMs}ms"),
                durationMs = (clockEpochMs() - start).coerceAtLeast(0L),
                retryDisposition = manifest.execution.retry
            )
        } catch (e: BrokerToolException) {
            ToolResult(
                requestId = request.requestId,
                status = e.status,
                error = FlowError(
                    e.errorCode,
                    SecretRedactor.redact(e.message)
                ),
                durationMs = (clockEpochMs() - start).coerceAtLeast(0L),
                retryDisposition = RetryDisposition.NO_RETRY
            )
        } catch (e: Exception) {
            ToolResult(
                requestId = request.requestId,
                status = ToolResultStatus.FAILED,
                error = FlowError(
                    ErrorCode.FAILED,
                    SecretRedactor.redact(e.message ?: "Tool execution failed")
                ),
                durationMs = (clockEpochMs() - start).coerceAtLeast(0L),
                retryDisposition = RetryDisposition.RECONCILE_FIRST
            )
        }

        return recordAndReturn(
            idempKey = idempKey,
            request = request,
            cacheResult = result.status == ToolResultStatus.OK,
            result = result
        )
    }

    private suspend fun recordAndReturn(
        idempKey: String,
        request: ToolRequest,
        cacheResult: Boolean,
        result: ToolResult
    ): ToolResult {
        idempotencyMutex.withLock {
            if (cacheResult) {
                cachedResults[idempKey] = result
            }
            receipts += ToolExecutionReceipt(
                requestId = request.requestId,
                idempotencyKey = request.idempotencyKey,
                toolId = request.toolId,
                toolVersion = request.toolVersion,
                status = result.status,
                timestampEpochMs = clockEpochMs(),
                redactedSummary = SecretRedactor.redact(result.error?.message ?: "status=${result.status}")
            )
        }
        return result
    }
}
