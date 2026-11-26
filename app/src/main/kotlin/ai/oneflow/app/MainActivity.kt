package ai.oneflow.app

import ai.oneflow.app.a2ui.A2UiSubsetView
import ai.oneflow.app.a2ui.A2UiValidator
import ai.oneflow.app.theme.OneflowColors
import ai.oneflow.app.theme.OneflowTheme
import ai.oneflow.app.ui.OneflowSessionController
import ai.oneflow.app.ui.OneflowUiState
import ai.oneflow.app.ui.RestrictedWorkspacePreviewCard
import ai.oneflow.capabilities.research.SsrfPolicyValidator
import ai.oneflow.capabilities.research.UrlPolicyCheck
import ai.oneflow.core.contracts.ChatRole
import ai.oneflow.core.contracts.MissionState
import ai.oneflow.core.contracts.ProviderPresets
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewFeature
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var controller: OneflowSessionController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = OneflowSessionController(applicationContext)
        setContent {
            OneflowTheme {
                val state by controller.uiState.collectAsState()
                OneflowRootScreen(state = state, controller = controller)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::controller.isInitialized) controller.dispose()
    }
}

@Composable
private fun OneflowRootScreen(
    state: OneflowUiState,
    controller: OneflowSessionController
) {
    var selectedTab by remember { mutableIntStateOf(if (state.providerState.onboardingCompleted) 0 else 4) }

    Scaffold(
        containerColor = OneflowColors.Background,
        bottomBar = {
            NavigationBar(containerColor = OneflowColors.Surface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("Chat") },
                    label = { Text("Chat") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("Missions") },
                    label = { Text("Missions (${state.allMissions.size})") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = {
                        controller.refreshWorkspaceFiles()
                        selectedTab = 2
                    },
                    icon = { Text("Files") },
                    label = { Text("Workspace") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Text("Web") },
                    label = { Text("Research") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Text("Setup") },
                    label = { Text("Setup") }
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when (selectedTab) {
                0 -> ChatTab(state = state, controller = controller, onGoSetup = { selectedTab = 4 })
                1 -> MissionsTab(state = state, controller = controller, onOpenChat = { selectedTab = 0 })
                2 -> WorkspaceTab(state = state, controller = controller)
                3 -> ResearchTab(state = state, controller = controller)
                else -> SetupTab(state = state, controller = controller)
            }
        }
    }
}

@Composable
private fun ChatTab(
    state: OneflowUiState,
    controller: OneflowSessionController,
    onGoSetup: () -> Unit
) {
    var promptInput by remember { mutableStateOf("") }
    val activeProfile = state.providerState.profiles.firstOrNull {
        it.config.id.value == state.providerState.activeProviderId
    } ?: state.providerState.profiles.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text("ONEFLOW", style = MaterialTheme.typography.titleMedium, color = OneflowColors.TextPrimary)
                Text(
                    text = activeProfile?.let { "${it.config.name} • ${it.selectedModelId}" }
                        ?: "No provider configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = OneflowColors.TextSecondary
                )
            }
            if (activeProfile == null) {
                TextButton(onClick = onGoSetup) { Text("Configure API") }
            }
        }

        state.previewEntryPath?.let { entry ->
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Workspace artifact: $entry",
                    style = MaterialTheme.typography.bodySmall,
                    color = OneflowColors.Success
                )
                OutlinedButton(onClick = { controller.setShowPreview(!state.showPreview) }) {
                    Text(if (state.showPreview) "Hide Preview" else "Open Preview")
                }
            }
            if (state.showPreview) {
                RestrictedWorkspacePreviewCard(
                    pathGuard = controller.workspacePathGuard,
                    entryRelativePath = entry,
                    onClose = { controller.setShowPreview(false) }
                )
            }
        }

        state.statusBanner?.let { banner ->
            Surface(
                color = OneflowColors.Surface,
                border = BorderStroke(1.dp, OneflowColors.Warning),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(banner, color = OneflowColors.Warning, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            val mission = state.activeMission
            if (mission == null) {
                Surface(
                    color = OneflowColors.Surface,
                    border = BorderStroke(1.dp, OneflowColors.Border),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Phase 1 Kernel Ready. Send a prompt to start a durable mission, or configure a provider / [TEST FIXTURE] in Setup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OneflowColors.TextSecondary,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                Surface(
                    color = OneflowColors.SurfaceElevated,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Mission ${mission.id.value.take(10)} • ${mission.state} • turns ${mission.usage.modelTurnsUsed}/${mission.budget.maxModelTurns} • tools ${mission.usage.toolCallsUsed}/${mission.budget.maxToolCalls}",
                        style = MaterialTheme.typography.labelSmall,
                        color = OneflowColors.TextSecondary,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                for (entry in mission.conversation) {
                    val roleLabel = when (entry.message.role) {
                        ChatRole.USER -> "You"
                        ChatRole.ASSISTANT -> "Assistant"
                        ChatRole.TOOL -> "Tool (${entry.message.toolCallId ?: "?"})"
                        ChatRole.SYSTEM -> "System"
                    }
                    Surface(
                        color = if (entry.message.role == ChatRole.USER) OneflowColors.SurfaceElevated else OneflowColors.Surface,
                        border = BorderStroke(1.dp, OneflowColors.Border),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(roleLabel, style = MaterialTheme.typography.labelSmall, color = OneflowColors.TextMuted)
                            entry.message.content?.let { txt ->
                                Spacer(Modifier.height(4.dp))
                                Text(txt, style = MaterialTheme.typography.bodyMedium, color = OneflowColors.TextPrimary)
                            }
                            if (entry.message.toolCalls.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Tool calls: " + entry.message.toolCalls.joinToString { "${it.name}(${it.id})" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OneflowColors.TextSecondary
                                )
                            }
                        }
                    }
                }

                if (state.streamingBuffer.isNotBlank() && state.isRunningMission) {
                    Surface(
                        color = OneflowColors.Surface,
                        border = BorderStroke(1.dp, OneflowColors.Border),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Streaming...", style = MaterialTheme.typography.labelSmall, color = OneflowColors.Success)
                            Spacer(Modifier.height(4.dp))
                            Text(state.streamingBuffer, style = MaterialTheme.typography.bodyMedium, color = OneflowColors.TextPrimary)
                        }
                    }
                }

                for (card in state.toolTraceCards) {
                    Surface(
                        color = OneflowColors.Surface,
                        border = BorderStroke(1.dp, OneflowColors.Border),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "${card.toolName} [${card.callId}] -> ${card.result?.status ?: "RUNNING"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (card.result != null) OneflowColors.Success else OneflowColors.Warning
                            )
                            Text(card.argumentsPreview, style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
                        }
                    }
                }

                state.pendingApproval?.let { approval ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = OneflowColors.SurfaceElevated),
                        border = BorderStroke(1.dp, OneflowColors.Warning),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(14.dp)) {
                            Text("Approval Required (${approval.risk})", style = MaterialTheme.typography.titleSmall, color = OneflowColors.Warning)
                            Text("Tool: ${approval.toolId}@${approval.toolVersion}", style = MaterialTheme.typography.bodyMedium, color = OneflowColors.TextPrimary)
                            Text(approval.reason, style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
                            Text("Target args: ${approval.redactedTarget}", style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextMuted)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { controller.resolveApproval(true) }) { Text("Approve Once") }
                                OutlinedButton(onClick = { controller.resolveApproval(false) }) { Text("Deny") }
                            }
                        }
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                label = { Text("Mission objective (try 'add' or 'approval')") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (state.isRunningMission) {
                OutlinedButton(onClick = { controller.cancelActiveMission() }) { Text("Stop") }
            } else {
                Button(
                    enabled = promptInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OneflowColors.TextPrimary,
                        contentColor = OneflowColors.Background
                    ),
                    onClick = {
                        val p = promptInput
                        promptInput = ""
                        controller.startNewMission(p)
                    }
                ) {
                    Text("Run")
                }
            }
        }
    }
}

@Composable
private fun MissionsTab(
    state: OneflowUiState,
    controller: OneflowSessionController,
    onOpenChat: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Durable Missions (FileMissionStore)", style = MaterialTheme.typography.titleMedium, color = OneflowColors.TextPrimary)
        if (state.allMissions.isEmpty()) {
            Text("No persisted missions yet.", style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
        } else {
            for (m in state.allMissions) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
                    border = BorderStroke(1.dp, OneflowColors.Border),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(m.objective.take(48), style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
                            Text(m.state.name, style = MaterialTheme.typography.labelSmall, color = OneflowColors.Warning)
                        }
                        Text(
                            "rev=${m.stateRevision} • turns=${m.usage.modelTurnsUsed}/${m.budget.maxModelTurns} • tools=${m.usage.toolCallsUsed}/${m.budget.maxToolCalls} • usage=${m.usage.usageAccuracy}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OneflowColors.TextSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            OutlinedButton(onClick = {
                                controller.selectAndResumeMission(m.id, runImmediately = false)
                                onOpenChat()
                            }) { Text("Inspect") }
                            if (!m.state.isTerminal && m.state != MissionState.WAITING_PERMISSION) {
                                Button(onClick = {
                                    controller.selectAndResumeMission(m.id, runImmediately = true)
                                    onOpenChat()
                                }) { Text("Resume") }
                            }
                            OutlinedButton(onClick = { controller.replayMissionReadOnly(m.id) }) { Text("Replay") }
                            OutlinedButton(onClick = { controller.forkMission(m.id) }) { Text("Fork") }
                        }
                    }
                }
            }
        }

        state.activeReceipt?.let { rec ->
            Card(
                colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
                border = BorderStroke(1.dp, OneflowColors.Border),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(12.dp)) {
                    Text("Mission Receipt", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
                    Text(
                        "reqRev=${rec.requirementsRevision} • state=${rec.finalState} • observedEffects=${rec.toolCallsObserved} • events=${rec.eventCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = OneflowColors.TextSecondary
                    )
                    if (rec.undoableTransactions.isNotEmpty()) {
                        OutlinedButton(onClick = { controller.undoTransaction(rec.undoableTransactions.first()) }) {
                            Text("Undo Latest Local Write (${rec.undoableTransactions.size} available)")
                        }
                    }
                }
            }
        }

        state.replayReport?.let { rep ->
            Card(
                colors = CardDefaults.cardColors(containerColor = OneflowColors.SurfaceElevated),
                border = BorderStroke(1.dp, OneflowColors.Border),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Read-Only Replay (${rep.frames.size} frames, externalSideEffects=${rep.externalSideEffectsExecuted})",
                        style = MaterialTheme.typography.titleSmall,
                        color = OneflowColors.Success
                    )
                    for (frame in rep.frames.takeLast(10)) {
                        Text(
                            "#${frame.sequence} ${frame.eventType} -> ${frame.stateAfter ?: "?"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OneflowColors.TextSecondary
                        )
                    }
                }
            }
        }

        if (state.activeEvents.isNotEmpty()) {
            HorizontalDivider(color = OneflowColors.Border)
            Text("Selected Mission Event Journal (${state.activeEvents.size})", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
            for (ev in state.activeEvents.takeLast(20)) {
                Text(
                    "#${ev.sequence} ${ev.type} [${ev.actorSource}] effect=${ev.effectStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OneflowColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun WorkspaceTab(
    state: OneflowUiState,
    controller: OneflowSessionController
) {
    var editorDraft by remember(state.selectedEditorPath, state.selectedEditorContent) {
        mutableStateOf(state.selectedEditorContent)
    }
    var newFilePath by remember { mutableStateOf("") }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Workspace & Dev Kits", style = MaterialTheme.typography.titleMedium, color = OneflowColors.TextPrimary)
            OutlinedButton(onClick = { controller.refreshWorkspaceFiles() }) { Text("Refresh") }
        }

        state.environmentManifest?.let { env ->
            Surface(color = OneflowColors.Surface, border = BorderStroke(1.dp, OneflowColors.Border), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Environment: ${env.projectTypes.joinToString()}", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
                    Text("Git branch: ${env.gitBranch ?: "none"} • AGENTS.md: ${env.hasAgentsMd}", style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = newFilePath,
                onValueChange = { newFilePath = it },
                label = { Text("New file path (e.g. notes.txt)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                enabled = newFilePath.isNotBlank(),
                onClick = {
                    val path = newFilePath.trim()
                    newFilePath = ""
                    controller.saveWorkspaceFile(path, "")
                    controller.openWorkspaceFile(path)
                }
            ) { Text("Create") }
        }

        if (state.workspaceFiles.isEmpty()) {
            Text("Workspace is empty. Create a file or run a FlowCode mission.", style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (f in state.workspaceFiles) {
                    OutlinedButton(onClick = { controller.openWorkspaceFile(f) }) { Text(f) }
                }
            }
        }

        state.selectedEditorPath?.let { path ->
            Card(
                colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
                border = BorderStroke(1.dp, OneflowColors.Border),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(12.dp)) {
                    Text("Editing: $path", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
                    OutlinedTextField(
                        value = editorDraft,
                        onValueChange = { editorDraft = it },
                        minLines = 6,
                        maxLines = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { controller.saveWorkspaceFile(path, editorDraft) }) {
                        Text("Save (Undoable)")
                    }
                }
            }
        }

        HorizontalDivider(color = OneflowColors.Border)
        Text("FlowPkg / Runtime Kit Compatibility", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
        for (kit in state.devKits) {
            Surface(color = OneflowColors.SurfaceElevated, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(kit.name, style = MaterialTheme.typography.bodyMedium, color = OneflowColors.TextPrimary)
                        Text(kit.status, style = MaterialTheme.typography.labelSmall, color = OneflowColors.Warning)
                    }
                    Text(kit.detail, style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun SetupTab(
    state: OneflowUiState,
    controller: OneflowSessionController
) {
    var selectedPreset by remember { mutableStateOf(ProviderPresets.all.first()) }
    var baseUrl by remember { mutableStateOf(selectedPreset.defaultBaseUrl) }
    var modelId by remember { mutableStateOf("") }
    val apiKeyState = rememberTextFieldState()

    val sampleA2Ui = remember {
        A2UiValidator.parseSurface(
            """
            {
              "version": "1.0",
              "surfaceId": "phase1-status",
              "rootId": "root",
              "components": [
                {"id": "root", "component": "Card", "title": "A2UI Validated Subset Probe", "child": "col"},
                {"id": "col", "component": "Column", "children": ["row", "desc"]},
                {"id": "row", "component": "Row", "children": ["b1", "b2"]},
                {"id": "b1", "component": "StatusBadge", "label": "Schema Verified", "tone": "success"},
                {"id": "b2", "component": "StatusBadge", "label": "No Local LLM", "tone": "neutral"},
                {"id": "desc", "component": "Text", "text": "Allowlisted Compose subset active without HTML/JS.", "style": "caption"}
              ]
            }
            """.trimIndent()
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Provider & FlowVault Setup", style = MaterialTheme.typography.titleMedium, color = OneflowColors.TextPrimary)
        Text(
            "Phase 1 • ${state.diagnostics.appId} • API ${state.diagnostics.sdkInt} • ${state.diagnostics.abis}",
            style = MaterialTheme.typography.bodySmall,
            color = OneflowColors.TextSecondary
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
            border = BorderStroke(1.dp, OneflowColors.Border),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(14.dp)) {
                Text(state.providerStatusMessage, style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (preset in ProviderPresets.all) {
                        OutlinedButton(
                            onClick = {
                                selectedPreset = preset
                                baseUrl = preset.defaultBaseUrl
                            }
                        ) { Text(preset.displayName) }
                    }
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL (${selectedPreset.displayName})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID (manual or discovered)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedSecureTextField(
                    state = apiKeyState,
                    label = {
                        val stored = controller.hasStoredKey("provider_${selectedPreset.id}")
                        Text(if (stored) "API Key (encrypted in Keystore — enter to replace)" else "API Key (encrypted in Keystore)")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.discoveredModels.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        for (m in state.discoveredModels.take(12)) {
                            OutlinedButton(onClick = { modelId = m }) { Text(m) }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !state.isTestingProvider,
                        onClick = {
                            val key = apiKeyState.text.toString()
                            apiKeyState.clearText()
                            controller.saveAndProbeProvider(
                                presetId = selectedPreset.id,
                                displayName = selectedPreset.displayName,
                                baseUrl = baseUrl,
                                modelId = modelId,
                                rawApiKey = key.ifBlank { null }
                            )
                        }
                    ) { Text(if (state.isTestingProvider) "Testing..." else "Save & Probe /models") }

                    OutlinedButton(onClick = { controller.deleteProvider(selectedPreset.id) }) {
                        Text("Delete Key")
                    }
                }

                HorizontalDivider(color = OneflowColors.Border)
                OutlinedButton(onClick = { controller.enableLocalTestFixture() }) {
                    Text("Use Local Test Fixture Server (No Real LLM)")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
            border = BorderStroke(1.dp, OneflowColors.Border),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(14.dp)) {
                Text("Scoped Workspace Permissions", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
                Text(
                    "Default deny: FlowCode cannot read or write workspace files unless explicitly granted here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OneflowColors.TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { controller.setWorkspacePermissions(read = !state.workspaceReadGranted, write = state.workspaceWriteGranted) }
                    ) {
                        Text(if (state.workspaceReadGranted) "Read: GRANTED" else "Read: DENIED")
                    }
                    OutlinedButton(
                        onClick = { controller.setWorkspacePermissions(read = state.workspaceReadGranted, write = !state.workspaceWriteGranted) }
                    ) {
                        Text(if (state.workspaceWriteGranted) "Write: GRANTED" else "Write: DENIED")
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
            border = BorderStroke(1.dp, if (state.emergencyStopped) OneflowColors.Error else OneflowColors.Border),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val ctx = LocalContext.current
            val mpLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                controller.mediaProjectionManager.onConsentResult(result.resultCode, result.data)
                controller.refreshComputerUseStatus()
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(14.dp)) {
                Text("FlowAccess & Computer Use (Phase 6)", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
                Text(
                    "Accessibility=${if (state.accessibilityEnabled) "CONNECTED" else "DISABLED"} · Shizuku=${state.shizukuStatus.note} · ScreenCapture=${state.mediaProjectionState.status.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OneflowColors.TextSecondary
                )
                state.lastUiSnapshotSummary?.let { snapSummary ->
                    Text("Snapshot: $snapSummary", style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Button(
                        onClick = {
                            if (state.emergencyStopped) controller.clearEmergencyStop()
                            else controller.triggerEmergencyStop()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.emergencyStopped) OneflowColors.Warning else OneflowColors.Error,
                            contentColor = OneflowColors.Background
                        )
                    ) {
                        Text(if (state.emergencyStopped) "Resume (Clear Stop)" else "EMERGENCY STOP")
                    }
                    OutlinedButton(onClick = { controller.refreshComputerUseStatus() }) {
                        Text("Refresh Status")
                    }
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) {
                        Text("Accessibility Settings")
                    }
                    OutlinedButton(onClick = { controller.requestShizukuPermissionOrBind() }) {
                        Text("Bind Shizuku")
                    }
                    OutlinedButton(onClick = {
                        mpLauncher.launch(controller.mediaProjectionManager.createConsentIntent())
                    }) {
                        Text("Grant Screen Capture")
                    }
                    OutlinedButton(onClick = {
                        controller.mediaProjectionManager.stopAndRevoke()
                        controller.refreshComputerUseStatus()
                    }) {
                        Text("Stop Capture")
                    }
                    OutlinedButton(onClick = { controller.captureActiveUiTreePreview() }) {
                        Text("Capture UI Tree")
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
            border = BorderStroke(1.dp, OneflowColors.Border),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(14.dp)) {
                Text("Capability & Feature Flags", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
                for (item in state.diagnostics.capabilities) {
                    Surface(color = OneflowColors.SurfaceElevated, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, color = OneflowColors.TextPrimary)
                                Text(item.status, style = MaterialTheme.typography.labelSmall, color = OneflowColors.Warning)
                            }
                            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
                        }
                    }
                }
            }
        }

        A2UiSubsetView(result = sampleA2Ui)
    }
}

@Composable
private fun ResearchTab(
    state: OneflowUiState,
    controller: OneflowSessionController
) {
    val queryState = rememberTextFieldState("")
    val urlState = rememberTextFieldState("https://en.wikipedia.org/wiki/Android_(operating_system)")
    val ssrfValidator = remember { SsrfPolicyValidator() }
    val uiScope = rememberCoroutineScope()
    var browserSpaceOpen by remember { mutableStateOf(false) }
    var manualTakeover by remember { mutableStateOf(false) }
    var activeBrowserUrl by remember { mutableStateOf<String?>(null) }
    var browserSecurityNote by remember { mutableStateOf("Browser Space idle") }
    val multiProfileSupported = remember {
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    }

    fun openGuardedBrowserUrl(candidate: String) {
        uiScope.launch {
            val check = withContext(Dispatchers.IO) { ssrfValidator.validate(candidate) }
            when (check) {
                is UrlPolicyCheck.Allowed -> {
                    activeBrowserUrl = check.uri.toString()
                    browserSpaceOpen = true
                    browserSecurityNote = "Loaded ${check.uri}"
                }
                is UrlPolicyCheck.Blocked -> {
                    browserSecurityNote = "Blocked by SSRF policy: ${check.reason}"
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Text("FlowWeb & Browser Space", style = MaterialTheme.typography.titleMedium, color = OneflowColors.TextPrimary)
        Text(
            text = "SSRF-guarded search, fetch, citation ledger, and isolated WebView Space. MULTI_PROFILE=${if (multiProfileSupported) "SUPPORTED" else "LEGACY_FALLBACK"}",
            style = MaterialTheme.typography.bodySmall,
            color = OneflowColors.TextSecondary
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
            border = BorderStroke(1.dp, OneflowColors.Border),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(14.dp)) {
                Text("Web Search (Wikipedia OpenSearch)", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
                OutlinedTextField(
                    state = queryState,
                    label = { Text("Search query") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { controller.runWebSearch(queryState.text.toString()) },
                    colors = ButtonDefaults.buttonColors(containerColor = OneflowColors.TextPrimary, contentColor = OneflowColors.Background)
                ) {
                    Text("Search Web")
                }
                Text("Status: ${state.researchStatus}", style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
            }
        }

        if (state.searchHits.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
                border = BorderStroke(1.dp, OneflowColors.Border),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(14.dp)) {
                    Text("Search Results (${state.searchHits.size})", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
                    for (hit in state.searchHits) {
                        Surface(
                            color = OneflowColors.SurfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(10.dp)) {
                                Text(hit.title, style = MaterialTheme.typography.bodyMedium, color = OneflowColors.TextPrimary)
                                Text(hit.canonicalUrl, style = MaterialTheme.typography.labelSmall, color = OneflowColors.TextSecondary)
                                if (hit.snippet.isNotBlank()) {
                                    Text(hit.snippet, style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { controller.fetchAndCiteUrl(hit.canonicalUrl) }) {
                                        Text("Fetch & Cite")
                                    }
                                    OutlinedButton(onClick = {
                                        openGuardedBrowserUrl(hit.canonicalUrl)
                                    }) {
                                        Text("Open Space")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
            border = BorderStroke(1.dp, OneflowColors.Border),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(14.dp)) {
                Text("Direct URL Fetch / Browser Space", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
                OutlinedTextField(
                    state = urlState,
                    label = { Text("HTTPS URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Button(
                        onClick = { controller.fetchAndCiteUrl(urlState.text.toString()) },
                        colors = ButtonDefaults.buttonColors(containerColor = OneflowColors.TextPrimary, contentColor = OneflowColors.Background)
                    ) {
                        Text("Fetch & Cite")
                    }
                    OutlinedButton(onClick = {
                        openGuardedBrowserUrl(urlState.text.toString().trim())
                    }) {
                        Text(if (browserSpaceOpen) "Reload Space" else "Open Browser Space")
                    }
                    OutlinedButton(onClick = {
                        manualTakeover = !manualTakeover
                        browserSecurityNote = if (manualTakeover) "Manual takeover active (automation paused)" else "Automation resumed"
                    }) {
                        Text(if (manualTakeover) "Resume Auto" else "Manual Takeover")
                    }
                    if (browserSpaceOpen) {
                        OutlinedButton(onClick = { browserSpaceOpen = false }) {
                            Text("Close Space")
                        }
                    }
                }
                Text(browserSecurityNote, style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
            }
        }

        if (browserSpaceOpen && activeBrowserUrl != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
                border = BorderStroke(1.dp, if (manualTakeover) OneflowColors.Warning else OneflowColors.Border),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = if (manualTakeover) "Browser Space [MANUAL TAKEOVER]" else "Browser Space [READ-ONLY SANDBOX]",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (manualTakeover) OneflowColors.Warning else OneflowColors.TextSecondary
                    )
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = false
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.domStorageEnabled = false
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val targetUrl = request?.url?.toString().orEmpty()
                                        val scheme = request?.url?.scheme?.lowercase()
                                        if (scheme != "https" && scheme != "http") {
                                            browserSecurityNote = "Navigation blocked: unsupported scheme '$scheme'"
                                            return true
                                        }
                                        return false
                                    }
                                }
                            }
                        },
                        update = { webView ->
                            val target = activeBrowserUrl ?: return@AndroidView
                            if (webView.url != target && (target.startsWith("https://") || target.startsWith("http://"))) {
                                webView.loadUrl(target)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                    )
                }
            }
        }

        state.researchReportMarkdown?.let { report ->
            Card(
                colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
                border = BorderStroke(1.dp, OneflowColors.Border),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(14.dp)) {
                    Text("Research Ledger Report", style = MaterialTheme.typography.titleSmall, color = OneflowColors.TextPrimary)
                    Text(report, style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
                }
            }
        }
    }
}
