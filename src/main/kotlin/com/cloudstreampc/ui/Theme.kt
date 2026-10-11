package com.cloudstreampc.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.cloudstreampc.core.Store

/** CloudStream's look; primary color and background follow the "Disposition" settings. */
object CsColors {
    val Primary: Color get() = Color(Store.settings.primaryColor)
    val Background: Color
        get() = when (Store.settings.theme) {
            "dark" -> Color(0xFF111111)
            "gray" -> Color(0xFF1C1C1E)
            else -> Color(0xFF000000)
        }
    val Sidebar: Color
        get() = when (Store.settings.theme) {
            "gray" -> Color(0xFF26262A)
            else -> Color(0xFF1A1A1A)
        }
    val Surface: Color
        get() = if (Store.settings.theme == "gray") Color(0xFF2A2A2E) else Color(0xFF1C1C1E)
    val SurfaceHigh: Color
        get() = if (Store.settings.theme == "gray") Color(0xFF34343A) else Color(0xFF26262A)
    val TextMuted = Color(0xFFA0A0A8)
    val Good = Color(0xFF4CAF50)
    val Error = Color(0xFFE5484D)
    val Badge = Color(0xCC000000)

    /** Choices offered in "Disposition", like the app's primary colors. */
    val primaryChoices = listOf(
        0xFF3D50FA to "Normal",
        0xFF7B61FF to "Violet",
        0xFFE91E63 to "Rose",
        0xFFF44336 to "Rouge",
        0xFFFF9800 to "Orange",
        0xFFFFC107 to "Jaune",
        0xFF4CAF50 to "Vert",
        0xFF00BCD4 to "Cyan",
        0xFF2196F3 to "Bleu",
        0xFF9E9E9E to "Gris",
    )
}

@Composable
fun CloudStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CsColors.Primary,
            onPrimary = Color.White,
            secondary = CsColors.Primary,
            secondaryContainer = CsColors.Primary.copy(alpha = 0.25f),
            onSecondaryContainer = Color.White,
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
