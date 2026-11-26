package ai.oneflow.app

import ai.oneflow.app.a2ui.A2UiSubsetView
import ai.oneflow.app.a2ui.A2UiValidator
import ai.oneflow.app.theme.OneflowColors
import ai.oneflow.app.theme.OneflowTheme
import ai.oneflow.app.ui.OneflowSessionController
import ai.oneflow.app.ui.OneflowUiState
import ai.oneflow.app.ui.RestrictedWorkspacePreviewCard
import ai.oneflow.core.contracts.ChatRole
import ai.oneflow.core.contracts.MissionState
import ai.oneflow.core.contracts.ProviderPresets
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    var selectedTab by remember { mutableIntStateOf(if (state.providerState.onboardingCompleted) 0 else 2) }

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
                    onClick = { selectedTab = 2 },
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
                0 -> ChatTab(state = state, controller = controller, onGoSetup = { selectedTab = 2 })
                1 -> MissionsTab(state = state, controller = controller, onOpenChat = { selectedTab = 0 })
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        }
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
