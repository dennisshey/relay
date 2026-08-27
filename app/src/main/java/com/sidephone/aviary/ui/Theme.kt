package com.sidephone.aviary.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.sidephone.aviary.data.Protocol

val Protocol.color: Color get() = Color(colorArgb)

/** iMessage-style palette: white/black grounds, iOS blue accent, gray incoming bubbles. */
object IOSColors {
    val Blue = Color(0xFF007AFF)
    val BlueDark = Color(0xFF0A84FF)
    val IncomingLight = Color(0xFFE9E9EB)
    val IncomingDark = Color(0xFF26262A)
    val SubtleTextLight = Color(0xFF8E8E93)
    val SubtleTextDark = Color(0xFF98989E)
    val AvatarGray = Color(0xFFA8AEB8)
}

private val LightColors = lightColorScheme(
    primary = IOSColors.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E9FB),
    onPrimaryContainer = Color(0xFF0B3B63),
    secondaryContainer = IOSColors.IncomingLight,
    onSecondaryContainer = Color.Black,
    surfaceVariant = IOSColors.IncomingLight,
    onSurfaceVariant = Color(0xFF3C3C43),
    outline = IOSColors.SubtleTextLight,
    surface = Color.White,
    background = Color.White,
    onSurface = Color.Black,
    onBackground = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = IOSColors.BlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1B4C74),
    onPrimaryContainer = Color(0xFFD6E9FB),
    secondaryContainer = IOSColors.IncomingDark,
    onSecondaryContainer = Color.White,
    surfaceVariant = IOSColors.IncomingDark,
    onSurfaceVariant = Color(0xFFEBEBF5),
    outline = IOSColors.SubtleTextDark,
    surface = Color.Black,
    background = Color.Black,
    onSurface = Color.White,
    onBackground = Color.White,
)

@Composable
fun AviaryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
