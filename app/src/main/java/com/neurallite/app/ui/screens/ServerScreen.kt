package com.neurallite.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.neurallite.app.models.ModelCatalog
import com.neurallite.app.ui.components.MetricCard
import com.neurallite.app.ui.components.RequestLogRow
import com.neurallite.app.viewmodel.NeuralliteViewModel

private val Accent = Color(0xFF00E5FF)
private val Success = Color(0xFF00E676)
private val Error = Color(0xFFFF5252)

@Composable
fun ServerScreen(viewModel: NeuralliteViewModel) {
    val context = LocalContext.current
    val serverRunning by viewModel.serverRunning.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()
    val visibleLogs by viewModel.serverEvents.collectAsState()
    val totalRequests by viewModel.totalRequests.collectAsState()
    val avgTokPerSec by viewModel.avgTokPerSec.collectAsState()
    val isInferring by viewModel.isInferring.collectAsState()
    val apiToken by viewModel.apiToken.collectAsState()
    val lanIp by viewModel.lanIp.collectAsState()
    val activePort by viewModel.activePort.collectAsState()
    val serverError by viewModel.serverError.collectAsState()
    val config by viewModel.config.collectAsState()

    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var showRegenerateDialog by rememberSaveable { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.startServer(context)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshLanIp()
    }

    val activeModelName = activeModelId?.let { ModelCatalog.getModelById(it)?.displayName } ?: "None"
    val displayPort = if (serverRunning) activePort else config.serverPort

    val listState = rememberLazyListState()
    var lastScrolledEventId by remember { mutableStateOf(visibleLogs.lastOrNull()?.id) }

    LaunchedEffect(visibleLogs.lastOrNull()?.id, serverError) {
        val newestId = visibleLogs.lastOrNull()?.id
        if (newestId != null && newestId != lastScrolledEventId) {
            lastScrolledEventId = newestId
            val headerCount = 5 + if (serverError != null) 1 else 0
            listState.animateScrollToItem(headerCount + visibleLogs.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // ── Start / Stop Toggle ──────────────────────────────────────────────
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (serverRunning) {
                        val infiniteTransition = rememberInfiniteTransition(label = "serverPulse")
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = EaseInOutCubic),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "serverPulseScale"
                        )

                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(Success.copy(alpha = 0.15f))
                        )
                    }

                    Button(
                        onClick = {
                            if (serverRunning) {
                                viewModel.stopServer(context)
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.startServer(context)
                                }
                            }
                        },
                        modifier = Modifier.size(100.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (serverRunning) Success else Color.White.copy(alpha = 0.15f),
                            contentColor = if (serverRunning) MaterialTheme.colorScheme.onSecondary else Accent
                        )
                    ) {
                        Icon(
                            imageVector = if (serverRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = if (serverRunning) "Stop" else "Start",
                            modifier = Modifier.size(40.dp),
                            tint = if (serverRunning) MaterialTheme.colorScheme.onSecondary else Accent
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (serverRunning) "Server Running" else "Server Stopped",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (serverRunning) Success else MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Model: $activeModelName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isInferring) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(onClick = { viewModel.stopInference() }) {
                        Text(
                            text = "Stop Active Inference",
                            color = Error,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // ── Server Error Banner ──────────────────────────────────────────────
        serverError?.let { err ->
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Error.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = Error,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearServerError() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss error",
                                tint = Error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── Endpoints ────────────────────────────────────────────────────────
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Endpoints",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    EndpointRow(
                        label = "Local",
                        url = "http://localhost:$displayPort/v1",
                        context = context
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    EndpointRow(
                        label = "LAN",
                        url = "http://$lanIp:$displayPort/v1",
                        context = context
                    )
                }
            }
        }

        // ── API Token ────────────────────────────────────────────────────────
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "API Token",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val tokenDisplay = when {
                            apiToken.isBlank() -> "Generating…"
                            tokenVisible -> apiToken
                            else -> "•".repeat(24)
                        }
                        Text(
                            text = tokenDisplay,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            color = Accent,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(
                            onClick = { tokenVisible = !tokenVisible },
                            enabled = apiToken.isNotBlank()
                        ) {
                            Icon(
                                imageVector = if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "Toggle visibility",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                copyToClipboard(context, "API Token", apiToken)
                            },
                            enabled = apiToken.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(onClick = { showRegenerateDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Regenerate",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ── Live Metrics ─────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "Requests",
                    value = "$totalRequests",
                    icon = Icons.Filled.Http,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                MetricCard(
                    title = "Avg tok/s",
                    value = "%.1f".format(avgTokPerSec),
                    icon = Icons.Filled.Speed,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }

        // ── Request Log Header ───────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Request Log",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
                if (visibleLogs.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearServerEvents() }) {
                        Text("Clear", color = Accent, fontSize = 12.sp)
                    }
                }
            }
        }

        if (visibleLogs.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No requests yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(visibleLogs, key = { it.id }) { event ->
                RequestLogRow(
                    method = event.method,
                    path = event.path,
                    statusCode = event.statusCode,
                    durationMs = event.durationMs,
                    timestamp = event.timestampMs
                )
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }

    // ── Regenerate Token Dialog ───────────────────────────────────────────
    if (showRegenerateDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateDialog = false },
            title = { Text("Regenerate API Token") },
            text = {
                Text("This will invalidate the current token. All connected clients will need to update their token.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showRegenerateDialog = false
                    viewModel.regenerateApiToken(context)
                }) {
                    Text("Regenerate", color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
private fun EndpointRow(label: String, url: String, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                copyToClipboard(context, "Endpoint", url)
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = Accent,
                fontSize = 13.sp
            )
        }
        Icon(
            imageVector = Icons.Filled.ContentCopy,
            contentDescription = "Copy",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clip.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}
