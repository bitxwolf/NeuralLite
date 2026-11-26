package com.neurallite.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.neurallite.app.models.ModelCatalog
import com.neurallite.app.models.ModelInfo
import com.neurallite.app.models.ModelState
import com.neurallite.app.ui.components.ModelCard
import com.neurallite.app.viewmodel.NeuralliteViewModel

private val Accent = Color(0xFF00E5FF)
private val Error = Color(0xFFFF5252)

@Composable
fun ModelsScreen(viewModel: NeuralliteViewModel) {
    val modelStates by viewModel.modelStates.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()
    val profile by viewModel.hardwareProfile.collectAsState()
    val storageInfo by viewModel.storageInfo.collectAsState()
    val modelError by viewModel.modelError.collectAsState()

    val tier = profile?.tier ?: 4
    val availRamGB = profile?.let { it.availRamBytes / 1_073_741_824.0 }

    val sortedModels = remember(tier) {
        ModelCatalog.models.sortedWith(
            compareByDescending<ModelInfo> { tier in it.recommendedForTiers }
                .thenBy { it.fileSizeGB }
        )
    }

    val usedGB = storageInfo.first / 1_073_741_824.0
    val freeGB = storageInfo.second / 1_073_741_824.0
    val totalGB = usedGB + freeGB
    val usedFraction = if (totalGB > 0) (usedGB / totalGB).toFloat().coerceIn(0f, 1f) else 0f

    val isAnyModelLoading = modelStates.values.any { it is ModelState.Loading }

    var pendingSwitchModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteDialog by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Storage Summary ──────────────────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Model Storage",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.4f)
                    )
                    Text(
                        text = "%.1f GB used · %.1f GB free".format(usedGB, freeGB),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(0.6f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { usedFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.1f)
                )
            }
        }

        // ── Hardware scan prompt when not yet scanned ────────────────────────
        if (profile == null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = Accent.copy(alpha = 0.1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Run Hardware Scan in Detect to check RAM & tier compatibility.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Accent
                    )
                }
            }
        }

        // ── Native Load / Engine Error Banner ────────────────────────────────
        modelError?.let { errorMsg ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = Error.copy(alpha = 0.14f)
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Error,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearModelError() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss error",
                            tint = Error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // ── Model List ───────────────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(sortedModels, key = { it.id }) { model ->
                val state = modelStates[model.id] ?: ModelState.NotDownloaded
                val isRecommended = profile != null && tier in model.recommendedForTiers
                val isRamWarning = availRamGB != null && model.ramRequiredGB > availRamGB

                ModelCard(
                    model = model,
                    state = state,
                    isRecommended = isRecommended,
                    isRamWarning = isRamWarning,
                    isAnyModelLoading = isAnyModelLoading,
                    onDownload = { viewModel.downloadModel(model) },
                    onLoad = {
                        if (!isAnyModelLoading) {
                            if (activeModelId != null && activeModelId != model.id) {
                                pendingSwitchModelId = model.id
                            } else {
                                viewModel.loadModel(model.id)
                            }
                        }
                    },
                    onUnload = { viewModel.unloadModel() },
                    onDelete = { showDeleteDialog = model.id },
                    onCancel = { viewModel.cancelDownload(model.id) }
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // ── Unload + Load Confirmation Dialog ─────────────────────────────────
    pendingSwitchModelId?.let { newModelId ->
        val currentName = activeModelId?.let { ModelCatalog.getModelById(it)?.displayName }
            ?: "current model"
        val newName = ModelCatalog.getModelById(newModelId)?.displayName ?: newModelId
        AlertDialog(
            onDismissRequest = { pendingSwitchModelId = null },
            title = { Text("Switch Model") },
            text = {
                Text("Unload \"$currentName\" and load \"$newName\"?")
            },
            confirmButton = {
                TextButton(
                    enabled = !isAnyModelLoading,
                    onClick = {
                        pendingSwitchModelId = null
                        viewModel.switchModel(newModelId)
                    }
                ) {
                    Text(
                        "Switch",
                        color = if (isAnyModelLoading) Accent.copy(alpha = 0.4f) else Accent
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitchModelId = null }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // ── Delete Confirmation Dialog ────────────────────────────────────────
    showDeleteDialog?.let { modelId ->
        val modelName = ModelCatalog.getModelById(modelId)?.displayName ?: modelId
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Model") },
            text = { Text("Delete \"$modelName\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = null
                    viewModel.deleteModel(modelId)
                }) {
                    Text("Delete", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}
