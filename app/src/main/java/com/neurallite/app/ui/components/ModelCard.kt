package com.neurallite.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neurallite.app.models.ModelInfo
import com.neurallite.app.models.ModelState
import com.neurallite.app.ui.theme.NeuralliteColors

/**
 * Card displaying model metadata, compatibility badges, and state-driven actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelCard(
    model: ModelInfo,
    state: ModelState,
    isRecommended: Boolean,
    isRamWarning: Boolean,
    isAnyModelLoading: Boolean = false,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit = {},
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val cardShape = RoundedCornerShape(16.dp)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = NeuralliteColors.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Header: name + param size + "Best Match" badge ──────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = model.displayName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = model.parameterSize,
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                }

                if (isRecommended) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Best Match",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NeuralliteColors.success,
                        modifier = Modifier
                            .background(
                                color = NeuralliteColors.success.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Quantization & non-blocking RAM warning badges ──────────
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = model.quantization,
                    fontSize = 12.sp,
                    color = NeuralliteColors.accent,
                    modifier = Modifier
                        .background(
                            color = NeuralliteColors.accent.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )

                if (isRamWarning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                color = NeuralliteColors.warning.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Insufficient RAM",
                            tint = NeuralliteColors.warning,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Insufficient RAM (%.1f GB req)".format(model.ramRequiredGB),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = NeuralliteColors.warning,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Info row: size · tok/s · context ────────────────────────
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfoItem(label = "Size", value = "%.1f GB".format(model.fileSizeGB))
                InfoItem(label = "Speed", value = "${model.estimatedTokensPerSec} tok/s")
                InfoItem(label = "Context", value = "${model.contextLength}")
            }

            // ── Tags ────────────────────────────────────────────────────
            if (model.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    model.tags.forEach { tag ->
                        Text(
                            text = tag,
                            fontSize = 11.sp,
                            color = NeuralliteColors.accent.copy(alpha = 0.85f),
                            modifier = Modifier
                                .background(
                                    color = NeuralliteColors.accent.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── State-dependent action area ─────────────────────────────
            AnimatedContent(
                targetState = state,
                contentKey = { it::class },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "model_state_transition",
            ) { currentState ->
                when (currentState) {
                    is ModelState.NotDownloaded -> {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeuralliteColors.accent,
                                contentColor = Color.Black,
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download",
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    is ModelState.Downloading -> {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val pct = (currentState.progress * 100).toInt()
                                Text(
                                    text = currentState.statusNote?.let { "$it ($pct%)" } ?: "$pct%",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = NeuralliteColors.accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onCancel) {
                                    Text(
                                        "Cancel",
                                        color = Color.White.copy(alpha = 0.75f),
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = { currentState.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = NeuralliteColors.accent,
                                trackColor = NeuralliteColors.accent.copy(alpha = 0.15f),
                            )
                        }
                    }

                    is ModelState.Downloaded -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = onLoad,
                                enabled = !isAnyModelLoading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeuralliteColors.accent,
                                    contentColor = Color.Black,
                                    disabledContainerColor = NeuralliteColors.surfaceVariant,
                                    disabledContentColor = Color.White.copy(alpha = 0.6f),
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Load Model", fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = onDelete) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete model",
                                    tint = NeuralliteColors.error.copy(alpha = 0.85f),
                                )
                            }
                        }
                    }

                    is ModelState.Loading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = NeuralliteColors.accent,
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Loading…",
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }

                    is ModelState.Loaded -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Active",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NeuralliteColors.success,
                                modifier = Modifier
                                    .background(
                                        color = NeuralliteColors.success.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(20.dp),
                                    )
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(onClick = onUnload) {
                                Text(
                                    text = "Unload",
                                    color = NeuralliteColors.accent,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            IconButton(onClick = onDelete) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete model",
                                    tint = NeuralliteColors.error.copy(alpha = 0.85f),
                                )
                            }
                        }
                    }

                    is ModelState.Error -> {
                        Column {
                            Text(
                                text = currentState.message,
                                fontSize = 12.sp,
                                color = NeuralliteColors.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Button(
                                    onClick = onDownload,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NeuralliteColors.error.copy(alpha = 0.2f),
                                        contentColor = NeuralliteColors.error,
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Retry",
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Retry", fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(onClick = onDelete) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete model",
                                        tint = NeuralliteColors.error.copy(alpha = 0.85f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.65f),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}
