package ai.oneflow.app

import ai.oneflow.app.a2ui.A2UiParseResult
import ai.oneflow.app.a2ui.A2UiSubsetView
import ai.oneflow.app.a2ui.A2UiValidator
import ai.oneflow.app.probe.CapabilityProbe
import ai.oneflow.app.theme.OneflowColors
import ai.oneflow.app.theme.OneflowTheme
import ai.oneflow.core.contracts.ModelId
import ai.oneflow.core.contracts.ProviderConfig
import ai.oneflow.core.contracts.ProviderId
import ai.oneflow.core.models.OpenAiCompatibleProvider
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val diagnostics = CapabilityProbe.inspect(applicationContext)
        setContent {
            OneflowTheme {
                Scaffold(
                    containerColor = OneflowColors.Background,
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Phase0Screen(
                        diagnostics = diagnostics,
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun Phase0Screen(
    diagnostics: ai.oneflow.app.probe.Phase0Diagnostics,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
    val apiKeyState = rememberTextFieldState()
    var connectionStatus by remember { mutableStateOf("Disconnected — enter an OpenAI-compatible URL and API key to probe /models") }
    var isProbing by remember { mutableStateOf(false) }

    val sampleA2Ui = remember {
        A2UiValidator.parseSurface(
            """
            {
              "version": "1.0",
              "surfaceId": "phase0-status",
              "rootId": "root",
              "components": [
                {"id": "root", "component": "Card", "title": "A2UI Validated Subset Probe", "child": "col"},
                {"id": "col", "component": "Column", "children": ["row", "div", "desc"]},
                {"id": "row", "component": "Row", "children": ["b1", "b2"]},
                {"id": "b1", "component": "StatusBadge", "label": "Schema Verified", "tone": "success"},
                {"id": "b2", "component": "StatusBadge", "label": "No Local LLM", "tone": "neutral"},
                {"id": "div", "component": "Divider"},
                {"id": "desc", "component": "Text", "text": "Allowlisted Compose subset parsed cleanly without HTML/JS execution.", "style": "caption"}
              ]
            }
            """.trimIndent()
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "ONEFLOW",
            style = MaterialTheme.typography.headlineSmall,
            color = OneflowColors.TextPrimary
        )
        Text(
            text = "Phase 0 Foundation (v0.0.1) • ${diagnostics.appId} • API ${diagnostics.sdkInt} • ${diagnostics.abis}",
            style = MaterialTheme.typography.bodySmall,
            color = OneflowColors.TextSecondary
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
            border = BorderStroke(1.dp, OneflowColors.Border),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(14.dp)
            ) {
                Text(
                    text = "External Model Gateway (BYO API)",
                    style = MaterialTheme.typography.titleSmall,
                    color = OneflowColors.TextPrimary
                )
                Text(
                    text = connectionStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = OneflowColors.TextSecondary
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedSecureTextField(
                    state = apiKeyState,
                    label = { Text("API Key (kept in memory only in Phase 0)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = !isProbing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OneflowColors.TextPrimary,
                        contentColor = OneflowColors.Background
                    ),
                    onClick = {
                        isProbing = true
                        connectionStatus = "Probing GET /models..."
                        scope.launch {
                            val provider = OpenAiCompatibleProvider(
                                config = ProviderConfig(
                                    id = ProviderId("manual"),
                                    name = "User Endpoint",
                                    baseUrl = baseUrl.trim(),
                                    keyAlias = "memory-only",
                                    defaultModel = ModelId("unknown")
                                ),
                                apiKeyProvider = { apiKeyState.text.toString() }
                            )
                            val result = provider.listModels()
                            connectionStatus = result.fold(
                                onSuccess = { cat -> "Connected: ${cat.models.size} models discovered from ${cat.source}" },
                                onFailure = { err -> "Probe failed: ${err.message ?: "unknown error"}" }
                            )
                            isProbing = false
                        }
                    }
                ) {
                    Text(if (isProbing) "Testing..." else "Test Connection (/models)")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = OneflowColors.Surface),
            border = BorderStroke(1.dp, OneflowColors.Border),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(14.dp)
            ) {
                Text(
                    text = "Phase 0 Capability & Isolation Status",
                    style = MaterialTheme.typography.titleSmall,
                    color = OneflowColors.TextPrimary
                )
                for (item in diagnostics.capabilities) {
                    Surface(
                        color = OneflowColors.SurfaceElevated,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, color = OneflowColors.TextPrimary)
                                Text(item.status, style = MaterialTheme.typography.labelSmall, color = OneflowColors.Warning)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = OneflowColors.TextSecondary)
                        }
                    }
                }
            }
        }

        A2UiSubsetView(result = sampleA2Ui)
    }
}
