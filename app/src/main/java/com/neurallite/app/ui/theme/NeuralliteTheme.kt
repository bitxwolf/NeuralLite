package com.neurallite.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

// ── Core palette ────────────────────────────────────────────────────────────────

private val DarkBackground = Color(0xFF0A0F1E)
private val Accent = Color(0xFF00E5FF)
private val Success = Color(0xFF00E676)
private val Warning = Color(0xFFFFAB40)
private val Surface = Color(0xFF111C2E)
private val Error = Color(0xFFFF5252)

private val OnDarkBackground = Color(0xFFE0E0E0)
private val OnSurface = Color(0xFFE0E0E0)
private val OnAccent = Color(0xFF00121A)
private val OnError = Color(0xFF1A0000)
private val SurfaceVariant = Color(0xFF1A2740)
private val Outline = Color(0xFF2A3F5F)
private val OutlineVariant = Color(0xFF1E3050)
private val InverseSurface = Color(0xFFE0E0E0)
private val InverseOnSurface = Color(0xFF0A0F1E)
private val InversePrimary = Color(0xFF006874)
private val Scrim = Color(0xFF000000)

// ── Material 3 dark color scheme ────────────────────────────────────────────────

private val NeuralliteDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = Color(0xFF003A42),
    onPrimaryContainer = Accent,
    secondary = Success,
    onSecondary = Color(0xFF001A0D),
    secondaryContainer = Color(0xFF003D1A),
    onSecondaryContainer = Success,
    tertiary = Warning,
    onTertiary = Color(0xFF1A1000),
    tertiaryContainer = Color(0xFF3D2800),
    onTertiaryContainer = Warning,
    error = Error,
    onError = OnError,
    errorContainer = Color(0xFF3D0000),
    onErrorContainer = Error,
    background = DarkBackground,
    onBackground = OnDarkBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Color(0xFFB0BEC5),
    outline = Outline,
    outlineVariant = OutlineVariant,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    inversePrimary = InversePrimary,
    scrim = Scrim,
    surfaceTint = Accent,
    surfaceBright = Color(0xFF1E3050),
    surfaceDim = Color(0xFF080C18),
    surfaceContainer = Color(0xFF0F1828),
    surfaceContainerHigh = Color(0xFF142030),
    surfaceContainerHighest = Color(0xFF1A2740),
    surfaceContainerLow = Color(0xFF0C1220),
    surfaceContainerLowest = Color(0xFF060A14),
)

// ── Typography ──────────────────────────────────────────────────────────────────

private val NeuralliteTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
    ),
)

// ── Shapes ──────────────────────────────────────────────────────────────────────

private val NeuralliteShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// ── Direct-access color object ──────────────────────────────────────────────────

/**
 * Provides direct access to Neurallite's semantic colors outside of
 * [MaterialTheme.colorScheme]. Useful for one-off tints that don't map
 * cleanly to a Material role (e.g. status indicators, charts).
 */
object NeuralliteColors {
    val background: Color = DarkBackground
    val accent: Color = Accent
    val success: Color = Success
    val warning: Color = Warning
    val surface: Color = Surface
    val error: Color = Error

    val onBackground: Color = OnDarkBackground
    val onSurface: Color = OnSurface
    val onAccent: Color = OnAccent
    val surfaceVariant: Color = SurfaceVariant
    val outline: Color = Outline
}

// ── Theme composable ────────────────────────────────────────────────────────────

/**
 * Neurallite Material 3 dark theme.
 *
 * Wraps [MaterialTheme] with the app's color scheme, typography, and shapes.
 * All screens and components should be rendered inside this composable.
 *
 * ```
 * NeuralliteTheme {
 *     Scaffold { … }
 * }
 * ```
 */
@Composable
fun NeuralliteTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = NeuralliteDarkColorScheme,
        typography = NeuralliteTypography,
        shapes = NeuralliteShapes,
        content = content,
    )
}
