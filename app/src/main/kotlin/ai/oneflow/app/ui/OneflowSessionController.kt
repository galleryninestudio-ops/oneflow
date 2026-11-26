package ai.oneflow.app.ui

import ai.oneflow.app.data.LocalFixtureServer
import ai.oneflow.app.data.ProviderRepository
import ai.oneflow.app.data.ProviderStoreState
import ai.oneflow.app.probe.CapabilityProbe
import ai.oneflow.app.probe.Phase0Diagnostics
import ai.oneflow.app.vault.AndroidKeystoreVault
import ai.oneflow.core.contracts.AcceptanceCheckResult
import ai.oneflow.core.contracts.MissionEventEnvelope
import ai.oneflow.core.contracts.MissionId
import ai.oneflow.core.contracts.MissionState
import ai.oneflow.core.contracts.MissionStateSnapshot
import ai.oneflow.core.contracts.ModelId
import ai.oneflow.core.contracts.ModelToolSpec
import ai.oneflow.core.contracts.PendingApproval
import ai.oneflow.core.contracts.ToolResult
import ai.oneflow.core.contracts.VerificationRequest
import ai.oneflow.core.contracts.VerificationResult
import ai.oneflow.core.contracts.VerificationStatus
import ai.oneflow.core.contracts.Verifier
import ai.oneflow.core.contracts.WorkspaceId
import ai.oneflow.core.kernel.FileMissionStore
import ai.oneflow.core.kernel.FlowContextCompiler
import ai.oneflow.core.kernel.KernelResult
import ai.oneflow.core.kernel.MissionController
import ai.oneflow.core.kernel.MissionRunner
import ai.oneflow.core.kernel.MissionRunnerUpdate
import ai.oneflow.core.models.OpenAiCompatibleProvider
import ai.oneflow.execution.broker.DefaultToolRegistry
import ai.oneflow.execution.broker.Phase1FixtureTools
import ai.oneflow.execution.broker.SafeToolBroker
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ToolTraceCard(
    val callId: String,
    val toolName: String,
    val argumentsPreview: String,
    val result: ToolResult? = null
)

data class OneflowUiState(
    val diagnostics: Phase0Diagnostics,
    val providerState: ProviderStoreState = ProviderStoreState(),
    val discoveredModels: List<String> = emptyList(),
    val providerStatusMessage: String = "Configure an OpenAI-compatible API key & endpoint, or enable the labeled local test fixture.",
    val isTestingProvider: Boolean = false,
    val activeMission: MissionStateSnapshot? = null,
    val activeEvents: List<MissionEventEnvelope> = emptyList(),
    val allMissions: List<MissionStateSnapshot> = emptyList(),
    val streamingBuffer: String = "",
    val toolTraceCards: List<ToolTraceCard> = emptyList(),
    val pendingApproval: PendingApproval? = null,
    val isRunningMission: Boolean = false,
    val statusBanner: String? = null
)

class OneflowSessionController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val vault = AndroidKeystoreVault(appContext)
    private val providerRepo = ProviderRepository(
        storageFile = File(appContext.filesDir, "oneflow/providers.json"),
        vault = vault
    )
    private val missionStore = FileMissionStore(File(appContext.filesDir, "oneflow/missions").toPath())
    private val missionController = MissionController(missionStore)
    private val toolRegistry = DefaultToolRegistry()
    private val toolBroker = SafeToolBroker(toolRegistry)
    private val fixtureTools: List<ModelToolSpec> = Phase1FixtureTools.registerAll(toolBroker)
    private val fixtureServer = LocalFixtureServer()

    private val verifier = object : Verifier {
        override suspend fun evaluate(request: VerificationRequest): VerificationResult {
            val hasText = !request.candidateResponseText.isNullOrBlank()
            return if (hasText) {
                VerificationResult(
                    status = VerificationStatus.PASS,
                    checks = listOf(AcceptanceCheckResult("response_non_empty", true, null, "Assistant produced final response")),
                    summary = "Phase 1 deterministic verification passed"
                )
            } else {
                VerificationResult(
                    status = VerificationStatus.NEEDS_WORK,
                    checks = listOf(AcceptanceCheckResult("response_non_empty", false, null, "Response was empty")),
                    summary = "Empty assistant output; provide a final summary."
                )
            }
        }
    }

    private val _uiState = MutableStateFlow(
        OneflowUiState(
            diagnostics = CapabilityProbe.inspect(appContext),
            providerState = providerRepo.load()
        )
    )
    val uiState: StateFlow<OneflowUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            refreshMissions()
            val saved = providerRepo.activeProfile()
            if (saved != null && saved.isLocalTestFixture) {
                val url = fixtureServer.start()
                providerRepo.saveProfile(
                    presetId = saved.config.id.value,
                    displayName = saved.config.name,
                    baseUrl = url,
                    modelId = saved.selectedModelId,
                    rawApiKey = null,
                    isLocalTestFixture = true
                )
                _uiState.update {
                    it.copy(
                        providerState = providerRepo.load(),
                        providerStatusMessage = "Local test fixture server active at $url"
                    )
                }
            }
        }
    }

    fun hasStoredKey(alias: String): Boolean = providerRepo.hasStoredKey(alias)

    fun saveAndProbeProvider(
        presetId: String,
        displayName: String,
        baseUrl: String,
        modelId: String,
        rawApiKey: String?
    ) {
        scope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isTestingProvider = true, providerStatusMessage = "Saving to Keystore & probing /models...") }
            val updated = providerRepo.saveProfile(
                presetId = presetId,
                displayName = displayName,
                baseUrl = baseUrl,
                modelId = modelId,
                rawApiKey = rawApiKey,
                isLocalTestFixture = false
            )
            val profile = providerRepo.activeProfile()
            if (profile == null) {
                _uiState.update { it.copy(isTestingProvider = false, providerStatusMessage = "No active profile") }
                return@launch
            }
            val provider = OpenAiCompatibleProvider(
                config = profile.config,
                apiKeyProvider = { providerRepo.resolveKey(profile.config.keyAlias) }
            )
            val modelsRes = provider.listModels()
            modelsRes.fold(
                onSuccess = { cat ->
                    val firstModel = profile.selectedModelId.ifBlank { cat.models.firstOrNull()?.value.orEmpty() }
                    val finalState = if (firstModel.isNotBlank() && firstModel != profile.selectedModelId) {
                        providerRepo.saveProfile(presetId, displayName, baseUrl, firstModel, null, false)
                    } else updated
                    _uiState.update {
                        it.copy(
                            providerState = finalState,
                            discoveredModels = cat.models.map { m -> m.value },
                            isTestingProvider = false,
                            providerStatusMessage = "Connected (${cat.models.size} models discovered). Key encrypted in Android Keystore."
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            providerState = updated,
                            isTestingProvider = false,
                            providerStatusMessage = "Saved profile (Keystore encrypted), /models probe failed: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun enableLocalTestFixture() {
        scope.launch(Dispatchers.IO) {
            val url = fixtureServer.start()
            val updated = providerRepo.saveProfile(
                presetId = "local_fixture",
                displayName = "[TEST FIXTURE] Loopback SSE",
                baseUrl = url,
                modelId = "fixture-sse-v1",
                rawApiKey = "fixture-local-key",
                isLocalTestFixture = true
            )
            _uiState.update {
                it.copy(
                    providerState = updated,
                    discoveredModels = listOf("fixture-sse-v1"),
                    providerStatusMessage = "Active: [TEST FIXTURE] loopback HTTP/SSE server at $url (not a real AI model)."
                )
            }
        }
    }

    fun deleteProvider(presetId: String) {
        scope.launch(Dispatchers.IO) {
            val updated = providerRepo.deleteProfileAndKey(presetId)
            _uiState.update {
                it.copy(
                    providerState = updated,
                    discoveredModels = emptyList(),
                    providerStatusMessage = "Removed provider profile and deleted Keystore secret."
                )
            }
        }
    }

    fun startNewMission(userPrompt: String) {
        val text = userPrompt.trim()
        if (text.isBlank() || _uiState.value.isRunningMission) return

        activeJob?.cancel()
        activeJob = scope.launch(Dispatchers.IO) {
            val profile = providerRepo.activeProfile()
            if (profile == null || profile.selectedModelId.isBlank()) {
                _uiState.update { it.copy(statusBanner = "Configure a provider and model ID in Setup first.") }
                return@launch
            }
            if (profile.isLocalTestFixture) {
                fixtureServer.start()
            }

            _uiState.update {
                it.copy(
                    isRunningMission = true,
                    streamingBuffer = "",
                    toolTraceCards = emptyList(),
                    pendingApproval = null,
                    statusBanner = null
                )
            }

            val created = missionController.createMission(
                workspaceId = WorkspaceId("default-workspace"),
                objective = text,
                allowedCapabilities = setOf("fixture.read", "fixture.write")
            )
            val runner = createRunner(profile.config)
            val withMsg = (runner.appendUserMessage(created.id, created.stateRevision, text) as KernelResult.Ok).value
            _uiState.update { it.copy(activeMission = withMsg) }
            runLoop(runner, withMsg.id, ModelId(profile.selectedModelId))
        }
    }

    fun selectAndResumeMission(missionId: MissionId, runImmediately: Boolean) {
        activeJob?.cancel()
        activeJob = scope.launch(Dispatchers.IO) {
            val snap = missionStore.load(missionId) ?: return@launch
            val events = missionStore.events(missionId)
            _uiState.update {
                it.copy(
                    activeMission = snap,
                    activeEvents = events,
                    pendingApproval = snap.pendingApproval,
                    streamingBuffer = "",
                    statusBanner = null
                )
            }
            if (!runImmediately || snap.state.isTerminal || snap.state == MissionState.WAITING_PERMISSION) return@launch

            val profile = providerRepo.activeProfile() ?: run {
                _uiState.update { it.copy(statusBanner = "Configure a provider to resume mission.") }
                return@launch
            }
            if (profile.isLocalTestFixture) fixtureServer.start()
            _uiState.update { it.copy(isRunningMission = true) }
            runLoop(createRunner(profile.config), missionId, ModelId(profile.selectedModelId))
        }
    }

    fun resolveApproval(approved: Boolean) {
        val mission = _uiState.value.activeMission ?: return
        activeJob?.cancel()
        activeJob = scope.launch(Dispatchers.IO) {
            val profile = providerRepo.activeProfile() ?: return@launch
            if (profile.isLocalTestFixture) fixtureServer.start()
            val runner = createRunner(profile.config)
            when (val res = runner.resolvePendingApproval(mission.id, approved)) {
                is KernelResult.Err -> _uiState.update { it.copy(statusBanner = res.error.message) }
                is KernelResult.Ok -> {
                    _uiState.update {
                        it.copy(
                            activeMission = res.value,
                            pendingApproval = null,
                            isRunningMission = true
                        )
                    }
                    runLoop(runner, mission.id, ModelId(profile.selectedModelId))
                }
            }
        }
    }

    fun cancelActiveMission() {
        activeJob?.cancel()
        _uiState.update { it.copy(isRunningMission = false, statusBanner = "Mission cancellation requested.") }
    }

    private suspend fun runLoop(runner: MissionRunner, missionId: MissionId, modelId: ModelId) {
        try {
            val res = runner.runMission(
                missionId = missionId,
                modelId = modelId,
                tools = fixtureTools
            ) { update ->
                when (update) {
                    is MissionRunnerUpdate.StateChanged -> {
                        val events = missionStore.events(missionId)
                        _uiState.update {
                            it.copy(
                                activeMission = update.snapshot,
                                activeEvents = events,
                                pendingApproval = update.snapshot.pendingApproval
                            )
                        }
                    }
                    is MissionRunnerUpdate.TextDelta -> {
                        _uiState.update { it.copy(streamingBuffer = it.streamingBuffer + update.delta) }
                    }
                    is MissionRunnerUpdate.ToolDispatched -> {
                        _uiState.update {
                            it.copy(
                                toolTraceCards = it.toolTraceCards + ToolTraceCard(
                                    callId = update.toolCall.id,
                                    toolName = update.toolCall.name,
                                    argumentsPreview = update.toolCall.argumentsJson
                                )
                            )
                        }
                    }
                    is MissionRunnerUpdate.ToolFinished -> {
                        _uiState.update { state ->
                            state.copy(
                                toolTraceCards = state.toolTraceCards.map { card ->
                                    if (card.callId == update.toolCall.id) card.copy(result = update.result) else card
                                }
                            )
                        }
                    }
                    is MissionRunnerUpdate.ApprovalNeeded -> {
                        _uiState.update { it.copy(pendingApproval = update.approval) }
                    }
                }
            }
            if (res is KernelResult.Err) {
                _uiState.update { it.copy(statusBanner = res.error.message) }
            }
        } finally {
            refreshMissions()
            _uiState.update { it.copy(isRunningMission = false) }
        }
    }

    private suspend fun refreshMissions() {
        val list = missionStore.listMissions()
        val activeId = _uiState.value.activeMission?.id
        val updatedActive = activeId?.let { missionStore.load(it) } ?: list.firstOrNull()
        val events = updatedActive?.let { missionStore.events(it.id) }.orEmpty()
        _uiState.update {
            it.copy(
                allMissions = list,
                activeMission = updatedActive,
                activeEvents = events,
                pendingApproval = updatedActive?.pendingApproval
            )
        }
    }

    private fun createRunner(config: ai.oneflow.core.contracts.ProviderConfig): MissionRunner {
        val provider = OpenAiCompatibleProvider(
            config = config,
            apiKeyProvider = { providerRepo.resolveKey(config.keyAlias) }
        )
        return MissionRunner(
            store = missionStore,
            controller = missionController,
            modelProvider = provider,
            toolRegistry = toolRegistry,
            toolBroker = toolBroker,
            verifier = verifier,
            contextCompiler = FlowContextCompiler(knownSecrets = { vault.knownSecrets() }),
            knownSecrets = { vault.knownSecrets() }
        )
    }

    fun dispose() {
        activeJob?.cancel()
        fixtureServer.stop()
    }
}
