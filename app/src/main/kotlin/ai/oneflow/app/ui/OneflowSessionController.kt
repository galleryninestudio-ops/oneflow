package ai.oneflow.app.ui

import ai.oneflow.app.computeruse.MediaProjectionConsentManager
import ai.oneflow.app.computeruse.OneflowAccessibilityService
import ai.oneflow.app.computeruse.ShizukuBridge
import ai.oneflow.app.data.LocalFixtureServer
import ai.oneflow.app.data.ProviderRepository
import ai.oneflow.app.data.ProviderStoreState
import ai.oneflow.app.probe.CapabilityProbe
import ai.oneflow.app.probe.Phase0Diagnostics
import ai.oneflow.app.vault.AndroidKeystoreVault
import ai.oneflow.app.worker.AndroidIsolatedQuickJsEngine
import ai.oneflow.capabilities.computeruse.ComputerUseTools
import ai.oneflow.capabilities.computeruse.FlowAccessRouter
import ai.oneflow.capabilities.computeruse.MediaProjectionState
import ai.oneflow.capabilities.computeruse.ShizukuBridgeStatus
import ai.oneflow.capabilities.development.DevKitCatalog
import ai.oneflow.capabilities.development.DevKitStatus
import ai.oneflow.capabilities.development.DevWorkspaceTools
import ai.oneflow.capabilities.files.ArtifactStore
import ai.oneflow.capabilities.files.TransactionJournal
import ai.oneflow.capabilities.files.WorkspaceFileTools
import ai.oneflow.capabilities.files.WorkspacePathGuard
import ai.oneflow.capabilities.research.FlowWebTools
import ai.oneflow.capabilities.research.ResearchLedger
import ai.oneflow.capabilities.research.SearchHit
import ai.oneflow.capabilities.research.SearchRouter
import ai.oneflow.capabilities.research.SsrfPolicyValidator
import ai.oneflow.capabilities.research.WebFetcher
import ai.oneflow.capabilities.research.WikipediaOpenSearchAdapter
import ai.oneflow.platform.runtime.FlowEnvironmentDetector
import ai.oneflow.platform.runtime.WorkspaceEnvironmentManifest
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
import ai.oneflow.core.intelligence.FlowReplayEngine
import ai.oneflow.core.intelligence.MissionReceipt
import ai.oneflow.core.intelligence.MissionReceiptGenerator
import ai.oneflow.core.intelligence.ReplayReport
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
import ai.oneflow.execution.flowcode.FlowCodeTools
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files

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
    val workspaceReadGranted: Boolean = false,
    val workspaceWriteGranted: Boolean = false,
    val previewEntryPath: String? = null,
    val showPreview: Boolean = false,
    val workspaceFiles: List<String> = emptyList(),
    val selectedEditorPath: String? = null,
    val selectedEditorContent: String = "",
    val environmentManifest: WorkspaceEnvironmentManifest? = null,
    val devKits: List<DevKitStatus> = emptyList(),
    val searchHits: List<SearchHit> = emptyList(),
    val researchReportMarkdown: String? = null,
    val researchStatus: String = "Keyless Wikipedia search + SSRF-safe fetcher ready.",
    val accessibilityEnabled: Boolean = false,
    val emergencyStopped: Boolean = false,
    val shizukuStatus: ShizukuBridgeStatus = ShizukuBridgeStatus(),
    val mediaProjectionState: MediaProjectionState = MediaProjectionState(),
    val lastUiSnapshotSummary: String? = null,
    val activeMission: MissionStateSnapshot? = null,
    val activeEvents: List<MissionEventEnvelope> = emptyList(),
    val activeReceipt: MissionReceipt? = null,
    val replayReport: ReplayReport? = null,
    val undoableTxIds: List<String> = emptyList(),
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
    private val workspaceDir = File(appContext.filesDir, "oneflow/workspaces/default").apply { mkdirs() }
    val workspacePathGuard = WorkspacePathGuard(workspaceDir.toPath())
    private val artifactStore = ArtifactStore(File(workspaceDir, ".oneflow/artifacts").toPath())
    private val transactionJournal = TransactionJournal(File(workspaceDir, ".oneflow/journal").toPath(), workspacePathGuard)

    private val missionStore = FileMissionStore(File(appContext.filesDir, "oneflow/missions").toPath())
    private val missionController = MissionController(missionStore)
    private val replayEngine = FlowReplayEngine(missionStore)
    private val toolRegistry = DefaultToolRegistry()
    private val toolBroker = SafeToolBroker(toolRegistry)
    private val fixtureTools: List<ModelToolSpec> = Phase1FixtureTools.registerAll(toolBroker)
    private val workspaceTools = WorkspaceFileTools(workspacePathGuard, artifactStore, transactionJournal).also {
        it.registerAll(toolBroker)
    }
    private val devTools = DevWorkspaceTools(workspacePathGuard).also {
        it.registerAll(toolBroker)
    }
    private val ssrfValidator = SsrfPolicyValidator()
    private val webFetcher = WebFetcher(ssrfValidator)
    private val searchRouter = SearchRouter(
        listOf(WikipediaOpenSearchAdapter(validator = ssrfValidator))
    )
    private val researchLedger = ResearchLedger()
    private val webTools = FlowWebTools(searchRouter, webFetcher, researchLedger).also {
        it.registerAll(toolBroker)
    }
    val flowAccessRouter = FlowAccessRouter(OneflowAccessibilityService.backend)
    private val computerUseTools = ComputerUseTools(flowAccessRouter).also {
        it.registerAll(toolBroker)
    }
    val mediaProjectionManager = MediaProjectionConsentManager(appContext, flowAccessRouter)
    private val shizukuBridge = ShizukuBridge(appContext) { st ->
        flowAccessRouter.updateShizukuStatus(st)
        _uiState.update { it.copy(shizukuStatus = st) }
    }

    private val isolatedQuickJsEngine = AndroidIsolatedQuickJsEngine(
        context = appContext,
        toolRegistry = toolRegistry,
        toolBroker = toolBroker,
        activeToolIds = {
            listOf("fs.list", "fs.glob", "fs.read", "fs.write", "fs.patch", "fs.grep", "verify.file", "artifact.create", "artifact.read")
        },
        missionGrantsProvider = { missionId ->
            kotlinx.coroutines.runBlocking {
                missionStore.load(missionId)?.allowedCapabilities ?: emptySet()
            }
        },
        sessionApprovalsProvider = { missionId ->
            kotlinx.coroutines.runBlocking {
                missionStore.load(missionId)?.approvedTools ?: emptySet()
            }
        },
        knownSecrets = { vault.knownSecrets() }
    )

    private val flowCodeMetaTools: List<ModelToolSpec> = FlowCodeTools.registerMetaTools(
        broker = toolBroker,
        registry = toolRegistry,
        scriptEngine = isolatedQuickJsEngine,
        missionStore = missionStore
    )
    private val activeModelTools: List<ModelToolSpec> = flowCodeMetaTools + fixtureTools

    private val fixtureServer = LocalFixtureServer()

    private val verifier = object : Verifier {
        override suspend fun evaluate(request: VerificationRequest): VerificationResult {
            val hasText = !request.candidateResponseText.isNullOrBlank()
            return if (hasText) {
                VerificationResult(
                    status = VerificationStatus.PASS,
                    checks = listOf(AcceptanceCheckResult("response_non_empty", true, null, "Assistant produced final response")),
                    summary = "Deterministic verification passed"
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
            providerState = providerRepo.load(),
            previewEntryPath = if (Files.exists(workspaceDir.toPath().resolve("index.html"))) "index.html" else null
        )
    )
    val uiState: StateFlow<OneflowUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            refreshMissions()
            refreshWorkspaceFiles()
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

    fun setWorkspacePermissions(read: Boolean, write: Boolean) {
        _uiState.update { it.copy(workspaceReadGranted = read, workspaceWriteGranted = write) }
    }

    fun setShowPreview(show: Boolean) {
        _uiState.update { it.copy(showPreview = show) }
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

            val grants = buildSet {
                add("fixture.read")
                add("fixture.write")
                if (_uiState.value.workspaceReadGranted) add("workspace.read")
                if (_uiState.value.workspaceWriteGranted) add("workspace.write")
            }

            val created = missionController.createMission(
                workspaceId = WorkspaceId("default-workspace"),
                objective = text,
                allowedCapabilities = grants
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

    fun replayMissionReadOnly(missionId: MissionId) {
        scope.launch(Dispatchers.IO) {
            val report = replayEngine.replayReadOnly(missionId)
            _uiState.update { it.copy(replayReport = report) }
        }
    }

    fun forkMission(missionId: MissionId) {
        scope.launch(Dispatchers.IO) {
            val forked = replayEngine.forkMission(missionId)
            refreshMissions()
            if (forked != null) {
                _uiState.update {
                    it.copy(
                        activeMission = forked,
                        statusBanner = "Forked mission ${forked.id.value} (independent grants)"
                    )
                }
            }
        }
    }

    fun undoTransaction(txId: String) {
        scope.launch(Dispatchers.IO) {
            val ok = transactionJournal.undo(txId)
            refreshMissions()
            refreshWorkspaceFiles()
            val hasHtml = Files.exists(workspaceDir.toPath().resolve("index.html"))
            _uiState.update {
                it.copy(
                    previewEntryPath = if (hasHtml) "index.html" else null,
                    statusBanner = if (ok) "Undid local file transaction $txId" else "Could not undo $txId"
                )
            }
        }
    }

    fun refreshWorkspaceFiles() {
        scope.launch(Dispatchers.IO) {
            val files = Files.walk(workspacePathGuard.rootRealPath).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map { workspacePathGuard.relativize(it) }
                    .filter { !it.startsWith(".oneflow") }
                    .sorted()
                    .limit(100)
                    .collect(java.util.stream.Collectors.toList())
            }
            val env = FlowEnvironmentDetector.detect(workspacePathGuard.rootRealPath)
            val kits = DevKitCatalog.inspectHostCapabilities(isInAppSandbox = true)
            _uiState.update {
                it.copy(
                    workspaceFiles = files,
                    environmentManifest = env,
                    devKits = kits
                )
            }
        }
    }

    fun openWorkspaceFile(relativePath: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val target = workspacePathGuard.resolveContained(relativePath)
                target.toFile().readText(Charsets.UTF_8)
            }.onSuccess { content ->
                _uiState.update {
                    it.copy(selectedEditorPath = relativePath, selectedEditorContent = content)
                }
            }.onFailure { err ->
                _uiState.update { it.copy(statusBanner = "Open failed: ${err.message}") }
            }
        }
    }

    fun saveWorkspaceFile(relativePath: String, content: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val target = workspacePathGuard.resolveContained(relativePath)
                val prev = if (Files.exists(target)) Files.readAllBytes(target) else null
                val nextBytes = content.toByteArray(Charsets.UTF_8)
                transactionJournal.recordWrite(relativePath, prev, nextBytes)
                Files.createDirectories(target.parent)
                Files.write(target, nextBytes)
            }.onSuccess {
                refreshWorkspaceFiles()
                refreshMissions()
                val hasHtml = Files.exists(workspaceDir.toPath().resolve("index.html"))
                _uiState.update {
                    it.copy(
                        selectedEditorContent = content,
                        previewEntryPath = if (hasHtml) "index.html" else null,
                        statusBanner = "Saved $relativePath (undoable via TransactionJournal)"
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(statusBanner = "Save failed: ${err.message}") }
            }
        }
    }

    fun runWebSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        scope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(researchStatus = "Searching keyless sources...") }
            val resp = searchRouter.search(q, limit = 6)
            val errSummary = if (resp.sourceErrors.isEmpty()) "OK" else resp.sourceErrors.entries.joinToString { "${it.key}: ${it.value}" }
            _uiState.update {
                it.copy(
                    searchHits = resp.hits,
                    researchStatus = "Found ${resp.hits.size} hits ($errSummary)"
                )
            }
        }
    }

    fun fetchAndCiteUrl(url: String) {
        scope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(researchStatus = "Fetching $url (SSRF-checked)...") }
            val res = webFetcher.fetch(url)
            res.fold(
                onSuccess = { doc ->
                    if (doc.challengeDetected != null) {
                        _uiState.update { it.copy(researchStatus = "Manual takeover required: ${doc.challengeDetected}") }
                    } else {
                        researchLedger.record(doc, doc.textContent.take(300))
                        val md = researchLedger.exportMarkdownReport("Oneflow Research", "Extracted & verified citations")
                        _uiState.update {
                            it.copy(
                                researchReportMarkdown = md,
                                researchStatus = "Cited '${doc.title}' (${doc.sourceId})"
                            )
                        }
                    }
                },
                onFailure = { err ->
                    _uiState.update { it.copy(researchStatus = "Blocked/failed: ${err.message}") }
                }
            )
        }
    }

    private suspend fun runLoop(runner: MissionRunner, missionId: MissionId, modelId: ModelId) {
        try {
            val res = runner.runMission(
                missionId = missionId,
                modelId = modelId,
                tools = activeModelTools
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
            refreshWorkspaceFiles()
            val hasHtml = Files.exists(workspaceDir.toPath().resolve("index.html"))
            _uiState.update {
                it.copy(
                    isRunningMission = false,
                    previewEntryPath = if (hasHtml) "index.html" else null
                )
            }
        }
    }

    private suspend fun refreshMissions() {
        val list = missionStore.listMissions()
        val activeId = _uiState.value.activeMission?.id
        val updatedActive = activeId?.let { missionStore.load(it) } ?: list.firstOrNull()
        val events = updatedActive?.let { missionStore.events(it.id) }.orEmpty()
        val undoables = transactionJournal.listRecords().filter { !it.undone }.map { it.transactionId }
        val receipt = updatedActive?.let {
            MissionReceiptGenerator.generate(missionStore, it.id, undoableTransactions = undoables)
        }
        _uiState.update {
            it.copy(
                allMissions = list,
                activeMission = updatedActive,
                activeEvents = events,
                activeReceipt = receipt,
                undoableTxIds = undoables,
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

    fun refreshComputerUseStatus() {
        val sh = shizukuBridge.refreshStatus()
        _uiState.update {
            it.copy(
                accessibilityEnabled = OneflowAccessibilityService.isConnected(),
                emergencyStopped = flowAccessRouter.isEmergencyStopped(),
                shizukuStatus = sh,
                mediaProjectionState = flowAccessRouter.getMediaProjectionState()
            )
        }
    }

    fun triggerEmergencyStop() {
        flowAccessRouter.triggerEmergencyStop("User tapped Emergency Stop")
        mediaProjectionManager.stopAndRevoke()
        _uiState.update {
            it.copy(
                emergencyStopped = true,
                mediaProjectionState = flowAccessRouter.getMediaProjectionState(),
                statusBanner = "EMERGENCY STOP ACTIVE: Device actuation halted & screen capture revoked."
            )
        }
    }

    fun clearEmergencyStop() {
        flowAccessRouter.clearEmergencyStop()
        _uiState.update {
            it.copy(
                emergencyStopped = false,
                statusBanner = "Emergency stop cleared."
            )
        }
    }

    fun requestShizukuPermissionOrBind() {
        shizukuBridge.requestPermissionOrBind()
        refreshComputerUseStatus()
    }

    fun captureActiveUiTreePreview() {
        scope.launch {
            val res = withContext(Dispatchers.IO) { flowAccessRouter.captureSnapshot() }
            val summary = res.fold(
                onSuccess = { s ->
                    "Epoch #${s.epoch} pkg=${s.packageName} nodes=${s.nodes.size} redacted=${s.redactedCount}"
                },
                onFailure = { e -> "Capture unavailable: ${e.message}" }
            )
            _uiState.update {
                it.copy(
                    accessibilityEnabled = OneflowAccessibilityService.isConnected(),
                    lastUiSnapshotSummary = summary
                )
            }
        }
    }

    fun dispose() {
        activeJob?.cancel()
        shizukuBridge.unbind()
        mediaProjectionManager.stopAndRevoke()
        fixtureServer.stop()
    }
}
