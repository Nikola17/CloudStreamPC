package com.cloudstreampc.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cloudstreampc.core.Store

/**
 * Outfit for titles (geometric, like the streaming platforms' brand fonts) and Inter for the
 * interface text. Both are open fonts (SIL OFL) bundled in the app.
 */
object AppFonts {
    val Display = FontFamily(
        Font("fonts/Outfit-Medium.ttf", FontWeight.Medium),
        Font("fonts/Outfit-SemiBold.ttf", FontWeight.SemiBold),
        Font("fonts/Outfit-Bold.ttf", FontWeight.Bold),
        Font("fonts/Outfit-ExtraBold.ttf", FontWeight.ExtraBold),
    )
    val Body = FontFamily(
        Font("fonts/Inter-Regular.ttf", FontWeight.Normal),
        Font("fonts/Inter-Medium.ttf", FontWeight.Medium),
        Font("fonts/Inter-SemiBold.ttf", FontWeight.SemiBold),
        Font("fonts/Inter-Bold.ttf", FontWeight.Bold),
    )
}

private val appTypography = Typography(
    displayLarge = TextStyle(fontFamily = AppFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = AppFonts.Display, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, lineHeight = 50.sp, letterSpacing = (-0.015).em),
    displaySmall = TextStyle(fontFamily = AppFonts.Display, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.01).em),
    headlineLarge = TextStyle(fontFamily = AppFonts.Display, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.01).em),
    headlineMedium = TextStyle(fontFamily = AppFonts.Display, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = AppFonts.Display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = AppFonts.Display, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = 0.005.em),
    titleMedium = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.01.em),
    labelMedium = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.02.em),
    labelSmall = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.04.em),
)

/** CloudStream's look; primary color and background follow the "Disposition" settings. */
object CsColors {
    val Primary: Color get() = Color(Store.settings.primaryColor)
    val Background: Color
        get() = when (Store.settings.theme) {
            "dark" -> Color(0xFF111111)
            "gray" -> Color(0xFF1C1C1E)
            "navy" -> Color(0xFF070B17)
            "wine" -> Color(0xFF120709)
            "forest" -> Color(0xFF06100B)
            else -> Color(0xFF000000)
        }
    val Sidebar: Color
        get() = when (Store.settings.theme) {
            "gray" -> Color(0xFF26262A)
            "navy" -> Color(0xFF0E1527)
            "wine" -> Color(0xFF1E0E12)
            "forest" -> Color(0xFF0D1A13)
            else -> Color(0xFF1A1A1A)
        }
    val Surface: Color
        get() = when (Store.settings.theme) {
            "gray" -> Color(0xFF2A2A2E)
            "navy" -> Color(0xFF131C33)
            "wine" -> Color(0xFF26131A)
            "forest" -> Color(0xFF13241B)
            else -> Color(0xFF1C1C1E)
        }
    val SurfaceHigh: Color
        get() = when (Store.settings.theme) {
            "gray" -> Color(0xFF34343A)
            "navy" -> Color(0xFF1C2744)
            "wine" -> Color(0xFF331A22)
            "forest" -> Color(0xFF1B3024)
            else -> Color(0xFF26262A)
        }
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
        typography = appTypography,
        content = content,
    )
}
