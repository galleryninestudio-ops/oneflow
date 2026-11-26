package ai.oneflow.core.kernel

import ai.oneflow.core.contracts.ChatMessage
import ai.oneflow.core.contracts.ChatRole
import ai.oneflow.core.contracts.ChatToolCall
import ai.oneflow.core.contracts.ContextCompileSpec
import ai.oneflow.core.contracts.CorrelationId
import ai.oneflow.core.contracts.EffectStatus
import ai.oneflow.core.contracts.ErrorCode
import ai.oneflow.core.contracts.EventId
import ai.oneflow.core.contracts.FlowError
import ai.oneflow.core.contracts.LeaseManager
import ai.oneflow.core.contracts.MissionConversationEntry
import ai.oneflow.core.contracts.MissionEventEnvelope
import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.MissionState
import ai.oneflow.core.contracts.MissionStateSnapshot
import ai.oneflow.core.contracts.MissionStore
import ai.oneflow.core.contracts.MissionTransitions
import ai.oneflow.core.contracts.ModelEvent
import ai.oneflow.core.contracts.ModelId
import ai.oneflow.core.contracts.ModelProvider
import ai.oneflow.core.contracts.ModelToolSpec
import ai.oneflow.core.contracts.PendingApproval
import ai.oneflow.core.contracts.RiskTier
import ai.oneflow.core.contracts.SecretRedactor
import ai.oneflow.core.contracts.TokenUsage
import ai.oneflow.core.contracts.ToolBroker
import ai.oneflow.core.contracts.ToolRegistry
import ai.oneflow.core.contracts.ToolRequest
import ai.oneflow.core.contracts.ToolRequestId
import ai.oneflow.core.contracts.ToolResult
import ai.oneflow.core.contracts.ToolResultStatus
import ai.oneflow.core.contracts.UsageAccuracy
import ai.oneflow.core.contracts.VerificationRequest
import ai.oneflow.core.contracts.VerificationStatus
import ai.oneflow.core.contracts.Verifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

sealed interface MissionRunnerUpdate {
    data class StateChanged(val snapshot: MissionStateSnapshot) : MissionRunnerUpdate
    data class TextDelta(val delta: String) : MissionRunnerUpdate
    data class ToolDispatched(val toolCall: ChatToolCall) : MissionRunnerUpdate
    data class ToolFinished(val toolCall: ChatToolCall, val result: ToolResult) : MissionRunnerUpdate
    data class ApprovalNeeded(val approval: PendingApproval) : MissionRunnerUpdate
}

/**
 * Durable Phase 1 agent control loop (§4.4).
 * Deterministic code owns state transitions, leases, budgets, tool dispatch,
 * effect intent/observed journaling, approval pauses, cancellation, and verification.
 */
class MissionRunner(
    private val store: MissionStore,
    private val controller: MissionController,
    private val modelProvider: ModelProvider,
    private val toolRegistry: ToolRegistry,
    private val toolBroker: ToolBroker,
    private val verifier: Verifier,
    private val leaseManager: LeaseManager = InMemoryLeaseManager(),
    private val contextCompiler: FlowContextCompiler = FlowContextCompiler(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
    private val knownSecrets: () -> Collection<String> = { emptyList() }
) {
    private fun redact(s: String): String = SecretRedactor.redact(s, knownSecrets())

    suspend fun appendUserMessage(
        missionId: MissionId,
        expectedRevision: Long,
        content: String
    ): KernelResult<MissionStateSnapshot> {
        val current = store.load(missionId)
            ?: return KernelResult.Err(FlowError(ErrorCode.UNAVAILABLE, "Mission ${missionId.value} not found"))
        if (current.stateRevision != expectedRevision) {
            return KernelResult.Err(FlowError(ErrorCode.REVISION_CONFLICT, "Revision mismatch"))
        }
        val nextTurn = (current.conversation.maxOfOrNull { it.turnIndex } ?: -1) + 1
        val now = clockEpochMs()
        val entry = MissionConversationEntry(
            turnIndex = nextTurn,
            message = ChatMessage(role = ChatRole.USER, content = redact(content)),
            timestampEpochMs = now
        )
        val nextState = current.copy(
            stateRevision = current.stateRevision + 1L,
            conversation = current.conversation + entry,
            updatedAtEpochMs = now
        )
        val nextSeq = (store.events(missionId).lastOrNull()?.sequence ?: 0L) + 1L
        store.append(
            MissionEventEnvelope(
                eventId = EventId("e-${UUID.randomUUID()}"),
                sequence = nextSeq,
                timestampEpochMs = now,
                missionId = missionId,
                type = "conversation.user_message",
                actorSource = "user",
                payload = buildJsonObject {
                    put("turnIndex", JsonPrimitive(nextTurn))
                    put("chars", JsonPrimitive(content.length))
                }
            ),
            nextState
        )
        return KernelResult.Ok(nextState)
    }

    suspend fun resolvePendingApproval(
        missionId: MissionId,
        approved: Boolean
    ): KernelResult<MissionStateSnapshot> {
        val current = store.load(missionId)
            ?: return KernelResult.Err(FlowError(ErrorCode.UNAVAILABLE, "Mission ${missionId.value} not found"))
        val pending = current.pendingApproval
            ?: return KernelResult.Err(FlowError(ErrorCode.INVALID_ARGUMENT, "No pending approval for mission"))
        if (current.state != MissionState.WAITING_PERMISSION) {
            return KernelResult.Err(FlowError(ErrorCode.INVALID_ARGUMENT, "Mission state ${current.state} is not WAITING_PERMISSION"))
        }

        val now = clockEpochMs()
        val nextSeq = (store.events(missionId).lastOrNull()?.sequence ?: 0L) + 1L
        val nextApproved = if (approved) {
            current.approvedTools + "${pending.toolId}@${pending.toolVersion}"
        } else {
            current.approvedTools
        }
        val nextConversation = if (approved) {
            current.conversation
        } else {
            val nextTurn = (current.conversation.maxOfOrNull { it.turnIndex } ?: -1) + 1
            current.conversation + MissionConversationEntry(
                turnIndex = nextTurn,
                message = ChatMessage(
                    role = ChatRole.TOOL,
                    content = """{"status":"DENIED","error":"User denied permission for ${pending.toolId}"}""",
                    toolCallId = pending.requestId.value
                ),
                timestampEpochMs = now
            )
        }
        val nextState = current.copy(
            state = MissionState.RUNNING,
            stateRevision = current.stateRevision + 1L,
            approvedTools = nextApproved,
            pendingApproval = null,
            conversation = nextConversation,
            updatedAtEpochMs = now
        )
        store.append(
            MissionEventEnvelope(
                eventId = EventId("e-${UUID.randomUUID()}"),
                sequence = nextSeq,
                timestampEpochMs = now,
                missionId = missionId,
                type = if (approved) "permission.approved" else "permission.denied",
                actorSource = "user",
                payload = buildJsonObject {
                    put("toolId", JsonPrimitive(pending.toolId))
                    put("toolVersion", JsonPrimitive(pending.toolVersion))
                    put("requestId", JsonPrimitive(pending.requestId.value))
                    put("argumentsJson", JsonPrimitive(pending.argumentsJson))
                }
            ),
            nextState
        )
        return KernelResult.Ok(nextState)
    }

    suspend fun runMission(
        missionId: MissionId,
        modelId: ModelId,
        tools: List<ModelToolSpec> = emptyList(),
        compileSpec: ContextCompileSpec = ContextCompileSpec(
            systemPolicyInstructions = "You are Oneflow, a precise engineering assistant. Complete the mission accurately using only authorized tools."
        ),
        holderId: String = "runner-${UUID.randomUUID()}",
        onUpdate: suspend (MissionRunnerUpdate) -> Unit = {}
    ): KernelResult<MissionStateSnapshot> {
        val lease = leaseManager.acquire(missionId, holderId)
            ?: return KernelResult.Err(FlowError(ErrorCode.REVISION_CONFLICT, "Mission ${missionId.value} is already leased"))

        try {
            var state = store.load(missionId)
                ?: return KernelResult.Err(FlowError(ErrorCode.UNAVAILABLE, "Mission ${missionId.value} not found"))

            if (state.state == MissionState.CREATED) {
                state = when (val r = controller.transition(missionId, state.stateRevision, MissionState.READY, "Initialize runner")) {
                    is KernelResult.Err -> return r
                    is KernelResult.Ok -> r.value
                }
                onUpdate(MissionRunnerUpdate.StateChanged(state))
            }
            if (state.state == MissionState.READY || state.state == MissionState.PAUSED || state.state == MissionState.RECOVERING) {
                state = when (val r = controller.transition(missionId, state.stateRevision, MissionState.RUNNING, "Enter running loop")) {
                    is KernelResult.Err -> return r
                    is KernelResult.Ok -> r.value
                }
                onUpdate(MissionRunnerUpdate.StateChanged(state))
            }

            // Reconcile unacknowledged intents after prior process crash before running new turns.
            state = reconcileUnackedIntents(state)

            // Execute any user-approved pending tool call that hasn't produced effect.observed yet.
            state = dispatchPendingApprovedCalls(state, onUpdate)
            if (state.state.isTerminal || state.state.isWaiting) {
                return KernelResult.Ok(state)
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                leaseManager.renew(lease)
                state = store.load(missionId)
                    ?: return KernelResult.Err(FlowError(ErrorCode.UNAVAILABLE, "Mission disappeared"))

                if (state.state.isTerminal || state.state.isWaiting) {
                    return KernelResult.Ok(state)
                }

                if (state.usage.modelTurnsUsed >= state.budget.maxModelTurns ||
                    state.usage.toolCallsUsed >= state.budget.maxToolCalls
                ) {
                    state = (controller.transition(
                        missionId,
                        state.stateRevision,
                        MissionState.PAUSED_BUDGET,
                        "Mission budget cap reached"
                    ) as KernelResult.Ok).value
                    onUpdate(MissionRunnerUpdate.StateChanged(state))
                    return KernelResult.Ok(state)
                }

                val compiled = contextCompiler.compile(state, modelId, tools, compileSpec)
                var streamedText = ""
                var completedEvent: ModelEvent.Completed? = null
                var failedEvent: ModelEvent.Failed? = null

                modelProvider.generate(compiled.request).collect { event ->
                    currentCoroutineContext().ensureActive()
                    when (event) {
                        is ModelEvent.TextDelta -> {
                            streamedText += event.text
                            onUpdate(MissionRunnerUpdate.TextDelta(event.text))
                        }
                        is ModelEvent.ReasoningDelta -> Unit
                        is ModelEvent.ToolCallCompleted -> Unit
                        is ModelEvent.UsageReported -> Unit
                        is ModelEvent.Completed -> completedEvent = event
                        is ModelEvent.Failed -> failedEvent = event
                    }
                }

                val fail = failedEvent
                if (fail != null) {
                    state = (controller.recordFailure(
                        missionId = missionId,
                        expectedRevision = state.stateRevision,
                        category = "model_${fail.error.code.name.lowercase()}",
                        detail = redact(fail.error.message)
                    ) as KernelResult.Ok).value
                    onUpdate(MissionRunnerUpdate.StateChanged(state))
                    if (state.state != MissionState.RUNNING || !fail.error.retryable) {
                        if (state.state == MissionState.RUNNING) {
                            state = (controller.transition(
                                missionId,
                                state.stateRevision,
                                MissionState.BLOCKED,
                                redact(fail.error.message),
                                error = fail.error
                            ) as KernelResult.Ok).value
                            onUpdate(MissionRunnerUpdate.StateChanged(state))
                        }
                        return KernelResult.Ok(state)
                    }
                    continue
                }

                val response = completedEvent ?: ModelEvent.Completed(
                    finishReason = null,
                    fullText = streamedText,
                    toolCalls = emptyList(),
                    usage = null
                )

                state = commitModelTurn(state, response.fullText, response.toolCalls, response.usage)
                onUpdate(MissionRunnerUpdate.StateChanged(state))

                if (state.state == MissionState.PAUSED_BUDGET) {
                    return KernelResult.Ok(state)
                }

                if (response.toolCalls.isNotEmpty()) {
                    for (toolCall in response.toolCalls) {
                        currentCoroutineContext().ensureActive()
                        state = store.load(missionId)!!
                        if (state.usage.toolCallsUsed >= state.budget.maxToolCalls) {
                            state = (controller.transition(
                                missionId,
                                state.stateRevision,
                                MissionState.PAUSED_BUDGET,
                                "Tool call budget cap reached"
                            ) as KernelResult.Ok).value
                            onUpdate(MissionRunnerUpdate.StateChanged(state))
                            return KernelResult.Ok(state)
                        }
                        val outcome = dispatchToolDurably(state, toolCall, onUpdate)
                        state = outcome
                        if (state.state.isWaiting || state.state.isTerminal) {
                            return KernelResult.Ok(state)
                        }
                    }
                    continue
                }

                // No tool calls -> must pass deterministic Verifier before COMPLETED.
                val evalRes = controller.evaluateCompletion(
                    missionId = missionId,
                    expectedRevision = state.stateRevision,
                    verifier = verifier,
                    candidateResponseText = response.fullText
                )
                when (evalRes) {
                    is KernelResult.Err -> return evalRes
                    is KernelResult.Ok -> {
                        val (nextState, verificationResult) = evalRes.value
                        state = nextState
                        onUpdate(MissionRunnerUpdate.StateChanged(state))
                        if (verificationResult.status == VerificationStatus.NEEDS_WORK) {
                            state = appendSystemFeedback(state, "Verification requested more work: ${verificationResult.summary}")
                            continue
                        }
                        return KernelResult.Ok(state)
                    }
                }
            }
        } catch (ce: CancellationException) {
            val latest = store.load(missionId)
            if (latest != null && !latest.state.isTerminal && MissionTransitions.canTransition(latest.state, MissionState.CANCELLED)) {
                runCatching {
                    controller.transition(
                        missionId = missionId,
                        expectedRevision = latest.stateRevision,
                        targetState = MissionState.CANCELLED,
                        reason = "Mission cancelled",
                        actorSource = "kernel"
                    )
                }.getOrNull()?.let { res ->
                    if (res is KernelResult.Ok) onUpdate(MissionRunnerUpdate.StateChanged(res.value))
                }
            }
            throw ce
        } finally {
            leaseManager.release(lease)
        }
    }

    private suspend fun reconcileUnackedIntents(initial: MissionStateSnapshot): MissionStateSnapshot {
        val events = store.events(initial.id)
        val intents = events.filter { it.type == "effect.intent" && it.effectStatus == EffectStatus.INTENT }
        val observedKeys = events.filter { it.type == "effect.observed" }
            .mapNotNull { it.payload["idempotencyKey"]?.jsonPrimitive?.contentOrNull }
            .toSet()
        val permissionHandledIds = events.filter { it.type == "permission.required" || it.type == "permission.approved" || it.type == "permission.denied" }
            .mapNotNull { it.payload["toolCallId"]?.jsonPrimitive?.contentOrNull ?: it.payload["requestId"]?.jsonPrimitive?.contentOrNull }
            .toSet()

        var state = initial
        for (intent in intents) {
            val idempKey = intent.payload["idempotencyKey"]?.jsonPrimitive?.contentOrNull ?: continue
            val toolCallId = intent.payload["toolCallId"]?.jsonPrimitive?.contentOrNull ?: continue
            val toolId = intent.payload["toolId"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            if (idempKey !in observedKeys && toolCallId !in permissionHandledIds) {
                val alreadyInConversation = state.conversation.any { it.message.toolCallId == toolCallId }
                if (!alreadyInConversation) {
                    val now = clockEpochMs()
                    val nextTurn = (state.conversation.maxOfOrNull { it.turnIndex } ?: -1) + 1
                    val nextState = state.copy(
                        stateRevision = state.stateRevision + 1L,
                        conversation = state.conversation + MissionConversationEntry(
                            turnIndex = nextTurn,
                            message = ChatMessage(
                                role = ChatRole.TOOL,
                                content = """{"status":"UNKNOWN_OUTCOME","error":"Process interrupted before tool acknowledgement for $toolId; reconcile before retrying."}""",
                                toolCallId = toolCallId
                            ),
                            timestampEpochMs = now
                        ),
                        updatedAtEpochMs = now
                    )
                    val nextSeq = (store.events(state.id).lastOrNull()?.sequence ?: 0L) + 1L
                    store.append(
                        MissionEventEnvelope(
                            eventId = EventId("e-${UUID.randomUUID()}"),
                            sequence = nextSeq,
                            timestampEpochMs = now,
                            missionId = state.id,
                            type = "effect.reconciled_unknown",
                            actorSource = "recovery",
                            effectStatus = EffectStatus.UNKNOWN,
                            payload = buildJsonObject {
                                put("idempotencyKey", JsonPrimitive(idempKey))
                                put("toolCallId", JsonPrimitive(toolCallId))
                                put("toolId", JsonPrimitive(toolId))
                            }
                        ),
                        nextState
                    )
                    state = nextState
                }
            }
        }
        return state
    }

    private suspend fun dispatchPendingApprovedCalls(
        initial: MissionStateSnapshot,
        onUpdate: suspend (MissionRunnerUpdate) -> Unit
    ): MissionStateSnapshot {
        val events = store.events(initial.id)
        val observedCallIds = events.filter { it.type == "effect.observed" }
            .mapNotNull { it.payload["toolCallId"]?.jsonPrimitive?.contentOrNull }
            .toSet()
        val approved = events.filter { it.type == "permission.approved" }
        var state = initial
        for (ev in approved) {
            val reqId = ev.payload["requestId"]?.jsonPrimitive?.contentOrNull ?: continue
            if (reqId in observedCallIds) continue
            if (state.conversation.any { it.message.toolCallId == reqId }) continue
            val toolId = ev.payload["toolId"]?.jsonPrimitive?.contentOrNull ?: continue
            val argsJson = ev.payload["argumentsJson"]?.jsonPrimitive?.contentOrNull ?: "{}"
            state = dispatchToolDurably(
                state,
                ChatToolCall(id = reqId, name = toolId, argumentsJson = argsJson),
                onUpdate
            )
            if (state.state.isWaiting || state.state.isTerminal) break
        }
        return state
    }

    private suspend fun commitModelTurn(
        current: MissionStateSnapshot,
        fullText: String,
        toolCalls: List<ChatToolCall>,
        usage: TokenUsage?
    ): MissionStateSnapshot {
        val now = clockEpochMs()
        val nextTurn = (current.conversation.maxOfOrNull { it.turnIndex } ?: -1) + 1
        val assistantMsg = ChatMessage(
            role = ChatRole.ASSISTANT,
            content = redact(fullText).ifBlank { null },
            toolCalls = toolCalls
        )
        val nextUsage = current.usage.copy(
            modelTurnsUsed = current.usage.modelTurnsUsed + 1,
            promptTokens = current.usage.promptTokens + (usage?.promptTokens ?: 0L),
            completionTokens = current.usage.completionTokens + (usage?.completionTokens ?: 0L),
            usageAccuracy = usage?.accuracy ?: if (current.usage.usageAccuracy == UsageAccuracy.PROVIDER_REPORTED) {
                UsageAccuracy.PROVIDER_REPORTED
            } else {
                UsageAccuracy.UNKNOWN
            }
        )
        val exhausted = nextUsage.modelTurnsUsed > current.budget.maxModelTurns
        val nextState = current.copy(
            state = if (exhausted) MissionState.PAUSED_BUDGET else current.state,
            stateRevision = current.stateRevision + 1L,
            usage = nextUsage,
            conversation = current.conversation + MissionConversationEntry(nextTurn, assistantMsg, now),
            updatedAtEpochMs = now
        )
        val nextSeq = (store.events(current.id).lastOrNull()?.sequence ?: 0L) + 1L
        store.append(
            MissionEventEnvelope(
                eventId = EventId("e-${UUID.randomUUID()}"),
                sequence = nextSeq,
                timestampEpochMs = now,
                missionId = current.id,
                type = "model.response_committed",
                actorSource = "model",
                payload = buildJsonObject {
                    put("turnIndex", JsonPrimitive(nextTurn))
                    put("toolCallsCount", JsonPrimitive(toolCalls.size))
                    put("usageAccuracy", JsonPrimitive(nextUsage.usageAccuracy.name))
                }
            ),
            nextState
        )
        return nextState
    }

    private suspend fun dispatchToolDurably(
        initial: MissionStateSnapshot,
        toolCall: ChatToolCall,
        onUpdate: suspend (MissionRunnerUpdate) -> Unit
    ): MissionStateSnapshot {
        val manifest = toolRegistry.get(toolCall.name)
        val version = manifest?.version ?: "1.0.0"
        val idempKey = "${initial.id.value}:${initial.usage.modelTurnsUsed}:${toolCall.id}"
        val parsedArgs: JsonObject = try {
            json.parseToJsonElement(toolCall.argumentsJson.ifBlank { "{}" }).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }

        // Persist effect.intent BEFORE executing tool
        val nowIntent = clockEpochMs()
        val intentSeq = (store.events(initial.id).lastOrNull()?.sequence ?: 0L) + 1L
        val afterIntentState = initial.copy(
            stateRevision = initial.stateRevision + 1L,
            updatedAtEpochMs = nowIntent
        )
        store.append(
            MissionEventEnvelope(
                eventId = EventId("e-${UUID.randomUUID()}"),
                sequence = intentSeq,
                timestampEpochMs = nowIntent,
                missionId = initial.id,
                type = "effect.intent",
                correlationId = CorrelationId(toolCall.id),
                actorSource = "kernel",
                effectStatus = EffectStatus.INTENT,
                payload = buildJsonObject {
                    put("toolId", JsonPrimitive(toolCall.name))
                    put("toolCallId", JsonPrimitive(toolCall.id))
                    put("idempotencyKey", JsonPrimitive(idempKey))
                }
            ),
            afterIntentState
        )

        onUpdate(MissionRunnerUpdate.ToolDispatched(toolCall))

        val result = toolBroker.invoke(
            request = ToolRequest(
                requestId = ToolRequestId(toolCall.id),
                idempotencyKey = idempKey,
                missionId = initial.id,
                toolId = toolCall.name,
                toolVersion = version,
                arguments = parsedArgs,
                expectedStateRevision = afterIntentState.stateRevision,
                correlationId = CorrelationId(toolCall.id)
            ),
            missionGrants = afterIntentState.allowedCapabilities,
            sessionApprovals = afterIntentState.approvedTools
        )

        onUpdate(MissionRunnerUpdate.ToolFinished(toolCall, result))

        val nowObs = clockEpochMs()
        val obsSeq = (store.events(initial.id).lastOrNull()?.sequence ?: 0L) + 1L
        val nextToolCount = afterIntentState.usage.toolCallsUsed + 1

        if (result.status == ToolResultStatus.APPROVAL_REQUIRED) {
            val pending = PendingApproval(
                requestId = ToolRequestId(toolCall.id),
                toolId = toolCall.name,
                toolVersion = version,
                risk = manifest?.risk ?: RiskTier.EXTERNAL_SIDE_EFFECT,
                reason = result.error?.message ?: "Approval required",
                redactedTarget = redact(toolCall.argumentsJson.take(200)),
                argumentsJson = redact(toolCall.argumentsJson)
            )
            val waitingState = afterIntentState.copy(
                state = MissionState.WAITING_PERMISSION,
                stateRevision = afterIntentState.stateRevision + 1L,
                pendingApproval = pending,
                updatedAtEpochMs = nowObs
            )
            store.append(
                MissionEventEnvelope(
                    eventId = EventId("e-${UUID.randomUUID()}"),
                    sequence = obsSeq,
                    timestampEpochMs = nowObs,
                    missionId = initial.id,
                    type = "permission.required",
                    correlationId = CorrelationId(toolCall.id),
                    actorSource = "broker",
                    effectStatus = EffectStatus.NONE,
                    payload = buildJsonObject {
                        put("toolId", JsonPrimitive(toolCall.name))
                        put("toolCallId", JsonPrimitive(toolCall.id))
                        put("idempotencyKey", JsonPrimitive(idempKey))
                    }
                ),
                waitingState
            )
            onUpdate(MissionRunnerUpdate.ApprovalNeeded(pending))
            onUpdate(MissionRunnerUpdate.StateChanged(waitingState))
            return waitingState
        }

        val toolContent = if (result.status == ToolResultStatus.OK) {
            redact(result.data?.toString() ?: "{}")
        } else {
            val safeErr = redact(result.error?.message ?: result.status.name)
            """{"status":"${result.status.name}","error":${JsonPrimitive(safeErr)}}"""
        }

        val nextTurn = (afterIntentState.conversation.maxOfOrNull { it.turnIndex } ?: -1) + 1
        val nextState = afterIntentState.copy(
            stateRevision = afterIntentState.stateRevision + 1L,
            usage = afterIntentState.usage.copy(toolCallsUsed = nextToolCount),
            conversation = afterIntentState.conversation + MissionConversationEntry(
                turnIndex = nextTurn,
                message = ChatMessage(
                    role = ChatRole.TOOL,
                    content = toolContent,
                    toolCallId = toolCall.id
                ),
                timestampEpochMs = nowObs
            ),
            updatedAtEpochMs = nowObs
        )
        store.append(
            MissionEventEnvelope(
                eventId = EventId("e-${UUID.randomUUID()}"),
                sequence = obsSeq,
                timestampEpochMs = nowObs,
                missionId = initial.id,
                type = "effect.observed",
                correlationId = CorrelationId(toolCall.id),
                actorSource = "broker",
                effectStatus = if (result.status == ToolResultStatus.OK) EffectStatus.COMMITTED else EffectStatus.FAILED,
                payload = buildJsonObject {
                    put("toolId", JsonPrimitive(toolCall.name))
                    put("toolCallId", JsonPrimitive(toolCall.id))
                    put("idempotencyKey", JsonPrimitive(idempKey))
                    put("status", JsonPrimitive(result.status.name))
                }
            ),
            nextState
        )

        if (result.status != ToolResultStatus.OK) {
            return (controller.recordFailure(
                missionId = initial.id,
                expectedRevision = nextState.stateRevision,
                category = "tool_${toolCall.name}",
                detail = result.error?.message ?: result.status.name
            ) as KernelResult.Ok).value
        }

        return nextState
    }

    private suspend fun appendSystemFeedback(current: MissionStateSnapshot, feedback: String): MissionStateSnapshot {
        val now = clockEpochMs()
        val nextTurn = (current.conversation.maxOfOrNull { it.turnIndex } ?: -1) + 1
        val nextState = current.copy(
            stateRevision = current.stateRevision + 1L,
            conversation = current.conversation + MissionConversationEntry(
                turnIndex = nextTurn,
                message = ChatMessage(role = ChatRole.SYSTEM, content = redact(feedback)),
                timestampEpochMs = now
            ),
            updatedAtEpochMs = now
        )
        val nextSeq = (store.events(current.id).lastOrNull()?.sequence ?: 0L) + 1L
        store.append(
            MissionEventEnvelope(
                eventId = EventId("e-${UUID.randomUUID()}"),
                sequence = nextSeq,
                timestampEpochMs = now,
                missionId = current.id,
                type = "verifier.feedback",
                actorSource = "verifier",
                payload = buildJsonObject { put("feedback", JsonPrimitive(redact(feedback))) }
            ),
            nextState
        )
        return nextState
    }
}
