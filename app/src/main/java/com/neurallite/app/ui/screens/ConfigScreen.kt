package com.neurallite.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neurallite.app.viewmodel.NeuralliteViewModel

private val Accent  = Color(0xFF00E5FF)
private val Error   = Color(0xFFFF5252)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(viewModel: NeuralliteViewModel) {
    val config by viewModel.config.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Configuration",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )

        // ── Inference Settings ───────────────────────────────────────────────
        SectionCard(title = "Inference") {
            // Context Window
            DropdownSetting(
                label = "Context Window",
                options = listOf(512, 1024, 2048, 4096),
                selected = config.contextWindow,
                onSelect = { viewModel.updateConfig(config.copy(contextWindow = it)) },
                format = { "$it tokens" }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Threads
            val threadOptions = listOf(-1, 2, 4)
            DropdownSetting(
                label = "CPU Threads",
                options = threadOptions,
                selected = config.threadCount,
                onSelect = { viewModel.updateConfig(config.copy(threadCount = it)) },
                format = { if (it == -1) "Auto" else "$it" }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Temperature Slider
            Text(
                text = "Temperature: ${"%.1f".format(config.temperature)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Slider(
                value = config.temperature,
                onValueChange = { viewModel.updateConfig(config.copy(temperature = it)) },
                valueRange = 0f..2f,
                steps = 19,
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Max Tokens
            DropdownSetting(
                label = "Max Tokens",
                options = listOf(256, 512, 1024, 2048),
                selected = config.maxTokens,
                onSelect = { viewModel.updateConfig(config.copy(maxTokens = it)) },
                format = { "$it" }
            )
        }

        // ── Server Settings ──────────────────────────────────────────────────
        SectionCard(title = "Server") {
            // Port
            var portText by remember { mutableStateOf(config.serverPort.toString()) }
            OutlinedTextField(
                value = portText,
                onValueChange = { text ->
                    portText = text
                    text.toIntOrNull()?.let { port ->
                        if (port in 1024..65535) {
                            viewModel.updateConfig(config.copy(serverPort = port))
                        }
                    }
                },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    cursorColor = Accent
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            ToggleSetting(
                label = "CORS Headers",
                description = "Allow cross-origin requests",
                checked = config.corsEnabled,
                onToggle = { viewModel.updateConfig(config.copy(corsEnabled = it)) }
            )

            ToggleSetting(
                label = "mDNS / Bonjour",
                description = "Advertise server on local network",
                checked = config.mdnsEnabled,
                onToggle = { viewModel.updateConfig(config.copy(mdnsEnabled = it)) }
            )

            ToggleSetting(
                label = "Wake Lock",
                description = "Keep CPU awake while server runs",
                checked = config.wakelockEnabled,
                onToggle = { viewModel.updateConfig(config.copy(wakelockEnabled = it)) }
            )
        }

        // ── App Info ─────────────────────────────────────────────────────────
        SectionCard(title = "App Info") {
            InfoItem(label = "Version", value = "1.0.0")
            InfoItem(label = "Package", value = "com.neurallite.app")
            InfoItem(label = "Model Path", value = "Android/data/…/files/models/")
        }

        // ── Danger Zone ──────────────────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Error.copy(alpha = 0.08f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Danger Zone",
                    style = MaterialTheme.typography.titleSmall,
                    color = Error,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { showClearDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear All Models")
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // ── Clear Models Dialog ──────────────────────────────────────────────
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Models") },
            text = { Text("This will delete all downloaded model files. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    // Delete all models
                    com.neurallite.app.models.ModelCatalog.models.forEach { model ->
                        viewModel.deleteModel(model.id)
                    }
                }) {
                    Text("Delete All", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

// ── Reusable Components ──────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun <T> DropdownSetting(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    format: (T) -> String
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Box {
            TextButton(onClick = { expanded = true }) {
                Text(text = format(selected), color = Accent)
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(format(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleSetting(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Accent,
                checkedTrackColor = Accent.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
