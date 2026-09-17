package com.clearcontent.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF0B6B5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6F1E7),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF3F5F59),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E8E3),
    onSecondaryContainer = Color(0xFF0F1F1C),
    tertiary = Color(0xFF3A5F8A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD4E3FF),
    onTertiaryContainer = Color(0xFF001C38),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFCE3DF),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF151D1B),
    surface = Color(0xFFF6F8F7),
    onSurface = Color(0xFF151D1B),
    surfaceVariant = Color(0xFFDCE5E2),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F4F2),
    surfaceContainer = Color(0xFFEAF0EE),
    surfaceContainerHigh = Color(0xFFE4EBE8),
    surfaceContainerHighest = Color(0xFFDEE6E3),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFC3CCC9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7ADBC8),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005045),
    onPrimaryContainer = Color(0xFFC6F1E7),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF2E4A45),
    onSecondaryContainer = Color(0xFFD6E8E3),
    tertiary = Color(0xFFA5C8F5),
    onTertiary = Color(0xFF02315B),
    tertiaryContainer = Color(0xFF214876),
    onTertiaryContainer = Color(0xFFD4E3FF),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF6B1E17),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1413),
    onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0E1413),
    onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C5),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF161D1B),
    surfaceContainer = Color(0xFF1A2120),
    surfaceContainerHigh = Color(0xFF242B2A),
    surfaceContainerHighest = Color(0xFF2F3634),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
)

/** Semantic colors that Material's scheme does not name. */
@Immutable
data class StatusColors(
    val ai: Color,
    val onAi: Color,
    val privacy: Color,
    val onPrivacy: Color,
    val info: Color,
    val onInfo: Color,
    val success: Color,
)

private val LightStatus = StatusColors(
    ai = Color(0xFFFCE3DF), onAi = Color(0xFF8C1D18),
    privacy = Color(0xFFFFF0D1), onPrivacy = Color(0xFF6A4A00),
    info = Color(0xFFE4EBE8), onInfo = Color(0xFF3F4946),
    success = Color(0xFF0B6B5E),
)

private val DarkStatus = StatusColors(
    ai = Color(0xFF5C1D18), onAi = Color(0xFFFFDAD6),
    privacy = Color(0xFF4A3A10), onPrivacy = Color(0xFFFFE3A3),
    info = Color(0xFF2F3634), onInfo = Color(0xFFBFC9C5),
    success = Color(0xFF7ADBC8),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp)

@Composable
fun ClearContentTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (dark) Dark else Light
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
