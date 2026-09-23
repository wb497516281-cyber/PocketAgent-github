package com.pocket.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * One deliberately dark scheme.
 *
 * Dynamic color answers with a different design on every wallpaper, and this
 * shell leans on translucent layers plus a single violet accent that have to
 * stay where they were put, so the app always renders with its own palette.
 */
private val PocketDarkColors = darkColorScheme(
    primary = Violet300,
    onPrimary = Violet900,
    primaryContainer = Violet900,
    onPrimaryContainer = Violet200,
    inversePrimary = Violet500,
    secondary = Teal300,
    onSecondary = Slate950,
    secondaryContainer = Teal900,
    onSecondaryContainer = Teal300,
    tertiary = Amber200,
    onTertiary = Slate950,
    tertiaryContainer = Amber900,
    onTertiaryContainer = Amber200,
    background = Slate950,
    onBackground = Slate100,
    surface = Slate950,
    onSurface = Slate100,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate200,
    surfaceContainerLowest = Slate960,
    surfaceContainerLow = Slate900,
    surfaceContainer = Slate900,
    surfaceContainerHigh = Slate850,
    surfaceContainerHighest = Slate800,
    surfaceDim = Slate960,
    surfaceBright = Slate850,
    inverseSurface = Slate100,
    inverseOnSurface = Slate950,
    outline = Color(0xFF67727F),
    outlineVariant = GlassBorder,
    error = Rose200,
    onError = Slate950,
    errorContainer = Rose900,
    onErrorContainer = Rose200,
    scrim = Slate960,
)

@Composable
fun PocketAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PocketDarkColors, content = content)
}
