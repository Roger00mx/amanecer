package com.rogeliorivera.amanecer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF14213D)
val CardBg = Color(0xFF1F2F55)
val Orange = Color(0xFFFCA311)
val TextMain = Color(0xFFF5F5F5)
val Muted = Color(0xFFB8C1D6)

@Composable
fun AmanecerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Orange,
            onPrimary = Navy,
            secondary = Orange,
            onSecondary = Navy,
            background = Navy,
            onBackground = TextMain,
            surface = CardBg,
            onSurface = TextMain,
            surfaceVariant = CardBg,
            onSurfaceVariant = Muted,
            secondaryContainer = Orange,
            onSecondaryContainer = Navy
        ),
        content = content
    )
}
