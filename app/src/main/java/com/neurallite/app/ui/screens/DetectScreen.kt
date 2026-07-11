package com.neurallite.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neurallite.app.ui.components.MetricCard
import com.neurallite.app.viewmodel.NeuralliteViewModel

// Theme accent colours (matching NeuralliteTheme)
private val Accent   = Color(0xFF00E5FF)
private val Success  = Color(0xFF00E676)
private val Warning  = Color(0xFFFFAB40)
private val Error    = Color(0xFFFF5252)

@Composable
fun DetectScreen(viewModel: NeuralliteViewModel) {
    val profile by viewModel.hardwareProfile.collectAsState()
    var isScanning by remember { mutableStateOf(false) }

    // Animated pulse for scan button
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Title ────────────────────────────────────────────────────────────
        Text(
            text = "Hardware Detection",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Scan your device to determine model compatibility",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Scan Button ──────────────────────────────────────────────────────
        Box(contentAlignment = Alignment.Center) {
            // Pulse ring behind button
            if (isScanning) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(Accent.copy(alpha = pulseAlpha * 0.2f))
                )
            }

            Button(
                onClick = {
                    isScanning = true
                    viewModel.scanHardware()
                },
                modifier = Modifier.size(120.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) Accent.copy(alpha = 0.3f) else Accent
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Radar,
                    contentDescription = "Scan",
                    modifier = Modifier.size(40.dp),
                    tint = Color.White
                )
            }
        }

        Text(
            text = if (isScanning && profile == null) "Scanning…" else "Run Hardware Scan",
            style = MaterialTheme.typography.labelLarge,
            color = Accent,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        // ── Tier Badge ───────────────────────────────────────────────────────
        profile?.let { hw ->
            LaunchedEffect(hw) { isScanning = false }

            val tierColor = when (hw.tier) {
                1 -> Success
                2 -> Accent
                3 -> Warning
                else -> Error
            }
            val tierLabel = when (hw.tier) {
                1 -> "TIER 1 — Flagship"
                2 -> "TIER 2 — Mid-range"
                3 -> "TIER 3 — Entry"
                else -> "TIER 4 — Minimal"
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = tierColor.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(tierColor)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = tierLabel,
                        color = tierColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Metric Cards Grid ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "Total RAM",
                    value = "%.1f GB".format(hw.totalRamBytes / 1_073_741_824.0),
                    icon = Icons.Filled.Memory,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "CPU Cores",
                    value = "${hw.cpuCores}",
                    icon = Icons.Filled.Speed,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "GPU",
                    value = hw.gpuRenderer.take(20),
                    icon = Icons.Filled.Memory,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Free Storage",
                    value = "%.1f GB".format(hw.freeStorageBytes / 1_073_741_824.0),
                    icon = Icons.Filled.Storage,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Capability Bar ───────────────────────────────────────────────
            Text(
                text = "Model Capability Range",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            CapabilityBar(tier = hw.tier)

            Spacer(modifier = Modifier.height(16.dp))

            // ── Extra info ───────────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    InfoRow("ABI", hw.cpuAbi)
                    InfoRow("API Level", "${hw.apiLevel}")
                    InfoRow("Available RAM", "%.1f GB".format(hw.availRamBytes / 1_073_741_824.0))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
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
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun CapabilityBar(tier: Int) {
    val models = listOf(
        "TinyLlama" to 4,
        "Phi-2" to 3,
        "Mistral-7B" to 2,
        "Llama 3 8B" to 1
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Progress track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.1f))
            ) {
                val fillFraction = when (tier) {
                    1 -> 1f
                    2 -> 0.75f
                    3 -> 0.50f
                    else -> 0.25f
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fillFraction)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Accent, Success)
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Model markers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                models.forEach { (name, reqTier) ->
                    val reachable = tier <= reqTier
                    Text(
                        text = name,
                        fontSize = 10.sp,
                        fontWeight = if (reachable) FontWeight.Bold else FontWeight.Normal,
                        color = if (reachable) Accent else Color.White.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}
