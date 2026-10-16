package com.freetv.live.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF8B73FF)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF5ED3F3),
    background = Color(0xFF0E0E14),
    surface = Color(0xFF16161F),
    surfaceVariant = Color(0xFF22222E),
    onBackground = Color(0xFFECECF4),
    onSurface = Color(0xFFECECF4),
    onSurfaceVariant = Color(0xFFA6A6B8),
)

@Composable
fun FreeTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
