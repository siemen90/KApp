package com.kapp.karaoke.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = KaraokePrimary,
    secondary = KaraokeSecondary,
    background = KaraokeBg,
    surface = KaraokeCard,
    onPrimary = KaraokeText,
    onSecondary = KaraokeText,
    onBackground = KaraokeText,
    onSurface = KaraokeText
)

@Composable
fun KaraokeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
