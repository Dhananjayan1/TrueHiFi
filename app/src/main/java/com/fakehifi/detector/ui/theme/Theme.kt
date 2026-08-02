package com.fakehifi.detector.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AmoledDarkScheme = darkColorScheme(
    primary = AccentTeal,
    onPrimary = AmoledBlack,
    background = AmoledBlack,
    surface = AmoledBlack,
    surfaceVariant = AmoledSurfaceVariant,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightScheme = lightColorScheme(
    primary = LightPrimary,
    background = LightBackground,
    surface = Color.White
)

/**
 * Dark mode uses true black (#000000) backgrounds rather than Material's
 * usual dark grey - that's what actually saves power on an AMOLED screen,
 * since black pixels are fully off rather than dimly lit.
 */
@Composable
fun TrueHiFiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) AmoledDarkScheme else LightScheme
    MaterialTheme(colorScheme = colorScheme, content = content)
}
