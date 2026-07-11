package com.neurallite.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neurallite.app.models.ModelCatalog
import com.neurallite.app.models.ModelInfo
import com.neurallite.app.models.ModelState
import com.neurallite.app.ui.components.ModelCard
import com.neurallite.app.viewmodel.NeuralliteViewModel

private val Accent  = Color(0xFF00E5FF)
private val Success = Color(0xFF00E676)

@Composable
fun ModelsScreen(viewModel: NeuralliteViewModel) {
    val modelStates   by viewModel.modelStates.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()
    val profile       by viewModel.hardwareProfile.collectAsState()

    val tier = profile?.tier ?: 4
    val availRamGB = (profile?.availRamBytes ?: 0L) / 1_073_741_824.0

    // Sort: recommended first, then by file size
    val sortedModels = remember(tier) {
        ModelCatalog.models.sortedWith(
            compareByDescending<ModelInfo> { tier in it.recommendedForTiers }
                .thenBy { it.fileSizeGB }
        )
    }

    // Storage info
    val storageInfo = remember { viewModel.getStorageInfo() }
    val usedGB = storageInfo.first / 1_073_741_824.0
    val freeGB = storageInfo.second / 1_073_741_824.0
    val totalGB = usedGB + freeGB
    val usedFraction = if (totalGB > 0) (usedGB / totalGB).toFloat() else 0f

    // Dialogs
    var showUnloadDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── Storage Summary ──────────────────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Model Storage",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "%.1f GB used · %.1f GB free".format(usedGB, freeGB),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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

        // ── Model List ───────────────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(sortedModels, key = { it.id }) { model ->
                val state = modelStates[model.id] ?: ModelState.NotDownloaded
                val isRecommended = tier in model.recommendedForTiers
                val isRamWarning = model.ramRequiredGB > availRamGB

                ModelCard(
                    model = model,
                    state = state,
                    isRecommended = isRecommended,
                    isRamWarning = isRamWarning,
                    onDownload = { viewModel.downloadModel(model) },
                    onLoad = {
                        if (activeModelId != null && activeModelId != model.id) {
                            val currentName = ModelCatalog.getModelById(activeModelId!!)?.displayName ?: "current model"
                            showUnloadDialog = model.id to currentName
                        } else {
                            viewModel.loadModel(model.id)
                        }
                    },
                    onDelete = { showDeleteDialog = model.id },
                    onCancel = { /* cancel download not implemented in basic DownloadManager */ }
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // ── Unload + Load Confirmation Dialog ─────────────────────────────────
    showUnloadDialog?.let { (newModelId, currentName) ->
        val newName = ModelCatalog.getModelById(newModelId)?.displayName ?: newModelId
        AlertDialog(
            onDismissRequest = { showUnloadDialog = null },
            title = { Text("Switch Model") },
            text = {
                Text("Unload \"$currentName\" and load \"$newName\"?\nThe server will restart.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnloadDialog = null
                    viewModel.unloadModel()
                    viewModel.loadModel(newModelId)
                }) {
                    Text("Switch", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnloadDialog = null }) {
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
                    Text("Delete", color = Color(0xFFFF5252))
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
