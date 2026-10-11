package com.cloudstreampc.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colors of CloudStream's default dark theme. */
object CsColors {
    val Primary = Color(0xFF3D50FA)
    val Background = Color(0xFF111111)
    val Surface = Color(0xFF1C1C1E)
    val SurfaceHigh = Color(0xFF26262A)
    val TextMuted = Color(0xFFA0A0A8)
    val Good = Color(0xFF4CAF50)
    val Error = Color(0xFFE5484D)
}

@Composable
fun CloudStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CsColors.Primary,
            onPrimary = Color.White,
            secondary = CsColors.Primary,
            background = CsColors.Background,
            onBackground = Color.White,
            surface = CsColors.Surface,
            onSurface = Color.White,
            surfaceVariant = CsColors.SurfaceHigh,
            onSurfaceVariant = CsColors.TextMuted,
            surfaceContainer = CsColors.Surface,
            surfaceContainerHigh = CsColors.SurfaceHigh,
            surfaceContainerHighest = CsColors.SurfaceHigh,
            error = CsColors.Error,
        ),
        content = content,
    )
}
