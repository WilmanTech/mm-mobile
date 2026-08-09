package com.wtm.musicmanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// MusicManager palette mirrors the desktop app
val BgBase = Color(0xFF121212)
val BgSidebar = Color(0xFF181818)
val BgCard = Color(0xFF282828)
val AccentPrimary = Color(0xFFFFD700)
val AccentHover = Color(0xFFE5C100)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB3B3B3)
val TextDisabled = Color(0xFF535353)

private val DarkColors = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = BgBase,
    secondary = AccentHover,
    background = BgBase,
    onBackground = TextPrimary,
    surface = BgCard,
    onSurface = TextPrimary,
    surfaceVariant = BgSidebar,
    onSurfaceVariant = TextSecondary,
)

@Composable
fun MusicManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // forced dark in v1
    content: @Composable () -> Unit,
) {
    // v1 is dark-only (matches desktop MusicManager UX)
    @Suppress("UNUSED_PARAMETER")
    val _unused = darkTheme
    MaterialTheme(
        colorScheme = DarkColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
