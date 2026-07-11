package com.neurallite.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
 * A feature-rich card that displays model metadata, state-dependent actions,
 * and contextual badges for the Neurallite model management screen.
 *
 * The card adapts its action area based on the current [ModelState], animating
 * transitions between download, loading, active, and error states. It also shows
 * a "Best Match" badge when [isRecommended] is true and overlays a RAM warning
 * when [isRamWarning] is set.
 *
 * @param model          The [ModelInfo] describing this model's metadata.
 * @param state          Current [ModelState] driving the action area UI.
 * @param isRecommended  When true, a green "Best Match" badge appears at top-right.
 * @param isRamWarning   When true, a translucent overlay with a RAM warning is shown.
 * @param onDownload     Invoked when the user taps Download (state: NotDownloaded).
 * @param onLoad         Invoked when the user taps Load Model (state: Downloaded).
 * @param onDelete       Invoked when the user taps the delete icon.
 * @param onCancel       Invoked when the user cancels an in-progress download.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelCard(
    model: ModelInfo,
    state: ModelState,
    isRecommended: Boolean,
    isRamWarning: Boolean,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val cardShape = RoundedCornerShape(16.dp)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = NeuralliteColors.surface),
    ) {
        Box {
            // ── Main content ────────────────────────────────────────────
            Column(modifier = Modifier.padding(16.dp)) {

                // ── Header: name + param size ───────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
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
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── Quantization badge ──────────────────────────────────
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

                Spacer(modifier = Modifier.height(12.dp))

                // ── Info row: size · tok/s · context ────────────────────
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    InfoItem(label = "Size", value = "%.1f GB".format(model.fileSizeGB))
                    InfoItem(label = "Speed", value = "${model.estimatedTokensPerSec} tok/s")
                    InfoItem(label = "Context", value = "${model.contextLength}")
                }

                // ── Tags ────────────────────────────────────────────────
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

                // ── State-dependent action area ─────────────────────────
                AnimatedContent(
                    targetState = state,
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
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = "${(currentState.progress * 100).toInt()}%",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = NeuralliteColors.accent,
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    TextButton(onClick = onCancel) {
                                        Text(
                                            "Cancel",
                                            color = Color.White.copy(alpha = 0.6f),
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
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NeuralliteColors.accent,
                                        contentColor = Color.Black,
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
                                        tint = NeuralliteColors.error.copy(alpha = 0.8f),
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
                                IconButton(onClick = onDelete) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete model",
                                        tint = NeuralliteColors.error.copy(alpha = 0.8f),
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
                                Button(
                                    onClick = onDownload,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NeuralliteColors.error.copy(alpha = 0.2f),
                                        contentColor = NeuralliteColors.error,
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Retry",
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Retry", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            // ── "Best Match" badge (top-right) ─────────────────────────
            if (isRecommended) {
                Text(
                    text = "Best Match",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NeuralliteColors.success,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .background(
                            color = NeuralliteColors.success.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            // ── RAM warning overlay ─────────────────────────────────────
            if (isRamWarning) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(cardShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Insufficient RAM",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuralliteColors.warning,
                    )
                }
            }
        }
    }
}

/**
 * Small label-value pair used inside [ModelCard] info rows.
 */
@Composable
private fun InfoItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.45f),
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}
