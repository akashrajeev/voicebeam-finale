package com.akashrajeev.voicebeam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF3DDBB0)
val Bg = Color(0xFF0D0F12)
val Card = Color(0xFF171A1F)
val Card2 = Color(0xFF1E2229)
val Muted = Color(0xFF8C939C)
val Dim = Color(0xFF5E656E)
val RecRed = Color(0xFFFF4D4F)

@Composable
fun VoiceBeamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color(0xFF00241A), background = Bg, surface = Card,
            surfaceVariant = Card2, onSurface = Color(0xFFF4F6F8), onBackground = Color(0xFFF4F6F8),
            secondary = Accent, error = RecRed,
        ),
        content = content,
    )
}
