package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GameVoxDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF00262C),
    primaryContainer = Color(0xFF004E5B),
    onPrimaryContainer = Color(0xFFB8F5FF),
    secondary = SaffronGold,
    onSecondary = Color(0xFF2E1E00),
    secondaryContainer = SaffronGoldContainer,
    onSecondaryContainer = Color(0xFFFFDF9E),
    tertiary = NeonEmerald,
    onTertiary = Color(0xFF003919),
    background = ObsidianBg,
    onBackground = TextPrimaryLight,
    surface = SlateSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = TextSecondaryMuted,
    error = CrimsonAlert,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GameVoxDarkColorScheme,
        typography = Typography,
        content = content
    )
}
