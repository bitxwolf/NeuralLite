package com.neurallite.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.neurallite.app.models.ModelCatalog
import com.neurallite.app.server.ServerEvent
import com.neurallite.app.ui.components.MetricCard
import com.neurallite.app.ui.components.RequestLogRow
import com.neurallite.app.viewmodel.NeuralliteViewModel
import java.text.SimpleDateFormat
import java.util.*

private val Accent  = Color(0xFF00E5FF)
private val Success = Color(0xFF00E676)
private val Error   = Color(0xFFFF5252)

@Composable
fun ServerScreen(viewModel: NeuralliteViewModel) {
    val context = LocalContext.current
    val serverRunning by viewModel.serverRunning.collectAsState()
    val activeModelId by viewModel.activeModelId.collectAsState()
    val serverEvents  by viewModel.serverEvents.collectAsState()

    var tokenVisible by remember { mutableStateOf(false) }
    var showRegenerateDialog by remember { mutableStateOf(false) }

    // Pulse animation for running state
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

    // Compute metrics
    val completedEvents = serverEvents.filterIsInstance<ServerEvent.RequestCompleted>()
    val totalRequests = completedEvents.size
    val avgTokPerSec = if (completedEvents.isNotEmpty()) {
        completedEvents.sumOf { it.tokensGenerated }.toFloat() /
            completedEvents.sumOf { it.durationMs.coerceAtLeast(1) }.toFloat() * 1000f
    } else 0f

    val activeModelName = activeModelId?.let { ModelCatalog.getModelById(it)?.displayName } ?: "None"

    // Real token from EncryptedSharedPreferences
    val apiToken by viewModel.apiToken.collectAsState()

    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new events
    LaunchedEffect(serverEvents.size) {
        if (serverEvents.isNotEmpty()) {
            listState.animateScrollToItem(serverEvents.size - 1)
        }
    }

    LazyColumn(
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
                            if (serverRunning) viewModel.stopServer(context)
                            else viewModel.startServer(context)
                        },
                        modifier = Modifier.size(100.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (serverRunning) Success else Color.White.copy(alpha = 0.15f)
                        )
                    ) {
                        Icon(
                            imageVector = if (serverRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = if (serverRunning) "Stop" else "Start",
                            modifier = Modifier.size(40.dp),
                            tint = if (serverRunning) Color.White else Accent
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (serverRunning) "Server Running" else "Server Stopped",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (serverRunning) Success else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )

                Text(
                    text = "Model: $activeModelName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
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
                        url = "http://localhost:8080/v1",
                        context = context
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    EndpointRow(
                        label = "LAN",
                        url = "http://192.168.1.x:8080/v1",
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
                        Text(
                            text = if (tokenVisible) apiToken else "•".repeat(32),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = Accent,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                imageVector = if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "Toggle visibility",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }

                        IconButton(onClick = {
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("API Token", apiToken))
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }

                        IconButton(onClick = { showRegenerateDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Regenerate",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }

        // ── Live Metrics ─────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "Requests",
                    value = "$totalRequests",
                    icon = Icons.Filled.Http,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Avg tok/s",
                    value = "%.1f".format(avgTokPerSec),
                    icon = Icons.Filled.Speed,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── Request Log ──────────────────────────────────────────────────────
        item {
            Text(
                text = "Request Log",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }

        if (completedEvents.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No requests yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(completedEvents.takeLast(50)) { event ->
                RequestLogRow(
                    method = "POST",
                    path = event.path,
                    statusCode = event.statusCode,
                    durationMs = event.durationMs,
                    timestamp = System.currentTimeMillis()
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
            text = { Text("This will invalidate the current token. All connected clients will need to update their token.") },
            confirmButton = {
                TextButton(onClick = { showRegenerateDialog = false }) {
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
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("Endpoint", url))
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
    }
}
