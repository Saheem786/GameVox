package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GameVoxLightColorScheme = lightColorScheme(
    primary = SoftBluePrimary,
    onPrimary = Color.White,
    primaryContainer = SoftBlueContainerLight,
    onPrimaryContainer = SoftBlueOnContainerLight,
    secondary = CleanEmerald,
    onSecondary = Color.White,
    secondaryContainer = CleanEmeraldContainerLight,
    onSecondaryContainer = CleanEmeraldOnContainerLight,
    tertiary = Color(0xFF6366F1),
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = DarkGreyText,
    surface = LightSurface,
    onSurface = DarkGreyText,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = MutedGreyTextLight,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = CleanRedError,
    onError = Color.White,
    errorContainer = CleanRedContainerLight,
    onErrorContainer = CleanRedOnContainerLight
)

private val GameVoxDarkColorScheme = darkColorScheme(
    primary = SoftBluePrimaryDark,
    onPrimary = Color(0xFF0F172A),
    primaryContainer = SoftBlueContainerDark,
    onPrimaryContainer = SoftBlueOnContainerDark,
    secondary = CleanEmeraldDark,
    onSecondary = Color(0xFF052E16),
    secondaryContainer = CleanEmeraldContainerDark,
    onSecondaryContainer = CleanEmeraldOnContainerDark,
    tertiary = Color(0xFF818CF8),
    onTertiary = Color(0xFF1E1B4B),
    background = DarkBackground,
    onBackground = LightTextOnDark,
    surface = DarkSurface,
    onSurface = LightTextOnDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = MutedGreyTextDark,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = CleanRedErrorDark,
    onError = Color.White,
    errorContainer = CleanRedContainerDark,
    onErrorContainer = CleanRedOnContainerDark
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) GameVoxDarkColorScheme else GameVoxLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
