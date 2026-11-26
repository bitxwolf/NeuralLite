package com.neurallite.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neurallite.app.viewmodel.NeuralliteViewModel
import kotlin.math.roundToInt

private val Accent = Color(0xFF00E5FF)
private val Warning = Color(0xFFFFAB40)
private val Error = Color(0xFFFF5252)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(viewModel: NeuralliteViewModel) {
    val config by viewModel.config.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()
    val loadedModelConfig by viewModel.loadedModelConfig.collectAsState()
    val serverRunning by viewModel.serverRunning.collectAsState()
    val activePort by viewModel.activePort.collectAsState()

    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    val availableCores = remember { Runtime.getRuntime().availableProcessors() }
    val threadOptions = remember(availableCores, config.threadCount) {
        val base = listOf(-1) + listOf(2, 4, 6, 8).filter { it <= availableCores }
        if (config.threadCount > 0 && config.threadCount !in base) {
            base + config.threadCount
        } else {
            base
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
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
            DropdownSetting(
                label = "Context Window",
                options = listOf(512, 1024, 2048, 4096),
                selected = config.contextWindow,
                onSelect = { viewModel.updateConfig(config.copy(contextWindow = it)) },
                format = { "$it tokens" }
            )

            Spacer(modifier = Modifier.height(12.dp))

            DropdownSetting(
                label = "CPU Threads",
                options = threadOptions,
                selected = config.threadCount,
                onSelect = { viewModel.updateConfig(config.copy(threadCount = it)) },
                format = { if (it == -1) "Auto" else "$it" }
            )

            Spacer(modifier = Modifier.height(12.dp))

            var sliderTemperature by remember(config.temperature) {
                mutableFloatStateOf(config.temperature)
            }
            Text(
                text = "Temperature: ${"%.1f".format(sliderTemperature)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Slider(
                value = sliderTemperature,
                onValueChange = { raw ->
                    sliderTemperature = (raw * 10f).roundToInt() / 10f
                },
                onValueChangeFinished = {
                    if (sliderTemperature != config.temperature) {
                        viewModel.updateConfig(config.copy(temperature = sliderTemperature))
                    }
                },
                valueRange = 0f..2f,
                steps = 19,
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            DropdownSetting(
                label = "Max Tokens",
                options = listOf(256, 512, 1024, 2048),
                selected = config.maxTokens,
                onSelect = { viewModel.updateConfig(config.copy(maxTokens = it)) },
                format = { "$it" }
            )

            val resolvedThreads = viewModel.resolvedThreadCount(config.threadCount)
            val modelReloadNeeded = activeModelId != null && (
                loadedModelConfig == null ||
                    loadedModelConfig?.first != config.contextWindow ||
                    loadedModelConfig?.second != resolvedThreads
                )
            if (modelReloadNeeded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Reload the active model to apply Context Window or CPU Thread changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning
                )
            }
        }

        // ── Server Settings ──────────────────────────────────────────────────
        SectionCard(title = "Server") {
            var portText by rememberSaveable { mutableStateOf(config.serverPort.toString()) }
            var lastSyncedPort by rememberSaveable { mutableStateOf(config.serverPort) }
            LaunchedEffect(config.serverPort) {
                if (config.serverPort != lastSyncedPort) {
                    lastSyncedPort = config.serverPort
                    if (portText.toIntOrNull() != config.serverPort) {
                        portText = config.serverPort.toString()
                    }
                }
            }
            val parsedPort = portText.toIntOrNull()
            val portInvalid = portText.isEmpty() || parsedPort == null || parsedPort !in 1024..65535

            OutlinedTextField(
                value = portText,
                onValueChange = { raw ->
                    val filtered = raw.filter { it.isDigit() }.take(5)
                    portText = filtered
                    filtered.toIntOrNull()?.let { port ->
                        if (port in 1024..65535) {
                            viewModel.updateConfig(config.copy(serverPort = port))
                        }
                    }
                },
                label = { Text("Port") },
                isError = portInvalid,
                supportingText = if (portInvalid) {
                    { Text("Port must be 1024–65535", color = Error) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    errorBorderColor = Error,
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

            if (serverRunning && config.serverPort != activePort) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Restart server to apply Port change ($activePort → ${config.serverPort}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Warning
                )
            }
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
                    viewModel.clearAllModels()
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.65f)
        )
    }
}
