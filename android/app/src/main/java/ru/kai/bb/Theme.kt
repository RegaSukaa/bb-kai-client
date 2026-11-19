package ru.kai.bb

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Палитра взята с логотипа: тёмный графит доски + золото рамки. */
private val Gold = Color(0xFFB07C14)
private val Graphite = Color(0xFF202226)

private val LightColors = lightColorScheme(
    primary = Graphite,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3A8),
    onPrimaryContainer = Color(0xFF2B1D00),
    secondary = Gold,
    onSecondary = Color.White,
    surface = Color.White,
    onSurface = Graphite,
    surfaceVariant = Color(0xFFF1F1F3),
    background = Color(0xFFF7F7F8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFD27A),
    onPrimary = Color(0xFF2B1D00),
    primaryContainer = Color(0xFF4A3A10),
    onPrimaryContainer = Color(0xFFFFE3A8),
    secondary = Color(0xFFE2B756),
    surface = Color(0xFF16181B),
    onSurface = Color(0xFFEDEDEF),
    surfaceVariant = Color(0xFF2A2C31),
    background = Color(0xFF101215),
)

@Composable
fun BbTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
