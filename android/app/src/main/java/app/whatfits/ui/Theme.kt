package app.whatfits.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WhatFitsColors = lightColorScheme(
    primary = Color(0xFF111827),
    onPrimary = Color.White,
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF2F4F7),
    onSurfaceVariant = Color(0xFF667085),
    error = Color(0xFFB42318),
)

@Composable
fun WhatFitsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WhatFitsColors,
        typography = Typography(),
        content = content,
    )
}
