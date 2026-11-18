package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SarvcutDarkColorScheme = darkColorScheme(
    primary = VioletPulse,
    onPrimary = TextPrimary,
    primaryContainer = VioletDeep,
    onPrimaryContainer = VioletSoft,
    secondary = CyanTelemetry,
    onSecondary = ObsidianBlack,
    secondaryContainer = ColorTokens.SecondaryContainer,
    onSecondaryContainer = CyanTelemetry,
    tertiary = EmeraldSuccess,
    onTertiary = ObsidianBlack,
    background = ObsidianBlack,
    onBackground = TextPrimary,
    surface = CarbonSurface,
    onSurface = TextPrimary,
    surfaceVariant = CarbonSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBlack,
    surfaceContainerLow = CarbonSurface,
    surfaceContainer = CarbonSurfaceElevated,
    surfaceContainerHigh = CarbonSurfaceHigh,
    outline = CarbonBorder,
    outlineVariant = ColorTokens.SubtleOutline,
    error = CrimsonError,
    onError = TextPrimary
)

private object ColorTokens {
    val SecondaryContainer = androidx.compose.ui.graphics.Color(0xFF102A33)
    val SubtleOutline = androidx.compose.ui.graphics.Color(0xFF1E2030)
}

val SarvcutShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun SarvcutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SarvcutDarkColorScheme,
        typography = SarvcutTypography,
        shapes = SarvcutShapes,
        content = content
    )
}
