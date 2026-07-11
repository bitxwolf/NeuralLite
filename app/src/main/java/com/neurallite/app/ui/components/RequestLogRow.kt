package com.neurallite.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neurallite.app.ui.theme.NeuralliteColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.Column

/**
 * A single request-log entry rendered in a monospace, terminal-style format.
 *
 * Output looks like: `14:32:07 · GET /health · 200 · 12ms`
 *
 * Status codes are color-coded:
 * - **2xx** → green ([NeuralliteColors.success])
 * - **4xx** → amber ([NeuralliteColors.warning])
 * - **5xx** → red ([NeuralliteColors.error])
 *
 * A subtle divider is drawn at the bottom for visual separation in lists.
 *
 * @param method     HTTP method (rendered uppercase).
 * @param path       Request path (e.g. `/v1/chat/completions`).
 * @param statusCode HTTP response status code.
 * @param durationMs Round-trip latency in milliseconds.
 * @param timestamp  Unix epoch milliseconds when the request was made.
 */
@Composable
fun RequestLogRow(
    method: String,
    path: String,
    statusCode: Int,
    durationMs: Long,
    timestamp: Long,
) {
    val timeString = remember(timestamp) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.format(Date(timestamp))
    }

    val statusColor = when {
        statusCode in 200..299 -> NeuralliteColors.success
        statusCode in 400..499 -> NeuralliteColors.warning
        statusCode in 500..599 -> NeuralliteColors.error
        else -> Color.White.copy(alpha = 0.6f)
    }

    val mutedColor = Color.White.copy(alpha = 0.5f)
    val separator = " · "

    val annotated = remember(method, path, statusCode, durationMs, timeString) {
        buildAnnotatedString {
            // Timestamp
            withStyle(SpanStyle(color = mutedColor)) {
                append(timeString)
            }
            withStyle(SpanStyle(color = mutedColor)) {
                append(separator)
            }
            // Method + path
            withStyle(SpanStyle(color = Color.White.copy(alpha = 0.85f))) {
                append(method.uppercase())
                append(" ")
                append(path)
            }
            withStyle(SpanStyle(color = mutedColor)) {
                append(separator)
            }
            // Status code (color-coded)
            withStyle(SpanStyle(color = statusColor)) {
                append(statusCode.toString())
            }
            withStyle(SpanStyle(color = mutedColor)) {
                append(separator)
            }
            // Duration
            withStyle(SpanStyle(color = mutedColor)) {
                append("${durationMs}ms")
            }
        }
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = annotated,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
        HorizontalDivider(
            color = Color.White.copy(alpha = 0.06f),
            thickness = 0.5.dp,
        )
    }
}
