package com.neurallite.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neurallite.app.ui.theme.NeuralliteColors

/**
 * A compact metric display card used throughout the Neurallite dashboard.
 */
@Composable
fun MetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)

    Card(
        modifier = modifier
            .border(
                width = 1.dp,
                color = NeuralliteColors.accent.copy(alpha = 0.15f),
                shape = shape,
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = NeuralliteColors.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = NeuralliteColors.accent,
                modifier = Modifier.size(28.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = title,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            val isLongValue = value.length > 12
            Text(
                text = value,
                fontSize = if (isLongValue) 14.sp else 20.sp,
                lineHeight = if (isLongValue) 18.sp else 24.sp,
                fontWeight = FontWeight.Bold,
                color = NeuralliteColors.accent,
                maxLines = if (isLongValue) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
