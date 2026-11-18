package app.fallbackmanual.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.fallbackmanual.R

/*
 * "Field manual meets rescue gear": rescue orange for emergencies, forest green for the calm parts
 * of the manual, warm paper and near-black ink, true black at night. Barlow (a typeface based on
 * road signs) for text, condensed for headings; JetBrains Mono for reference codes and labels.
 * Fonts are bundled: the app works offline. Their licenses are in assets/licenses/.
 */

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    Font(R.font.barlow_condensed_extrabold, FontWeight.ExtraBold),
)
val Mono = FontFamily(
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** The app's own colors. Material's color scheme is derived from these. */
@Immutable
data class FmColors(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val rule: Color,
    val nav: Color,
    val forest: Color,
    val forestBg: Color,
    val draftBg: Color,
    val draftInk: Color,
    val chipBg: Color,
    val chipInk: Color,
    /** Step numbers and the active tab: orange dark enough to read on paper (or light enough on black). */
    val accentText: Color,
    val activePill: Color,
    val orange: Color = Color(0xFFFF5B1F),
    val onOrange: Color = Color(0xFF16181B),
    val emergency: Color = Color(0xFFB3261E),
    val onEmergency: Color = Color(0xFFFFFFFF),
)

val LightFm = FmColors(
    dark = false,
    bg = Color(0xFFF3F0E8), surface = Color(0xFFFFFFFF), ink = Color(0xFF16181B), muted = Color(0xFF575C55),
    rule = Color(0xFFD9D4C7), nav = Color(0xFFFBF9F4), forest = Color(0xFF1E4A38), forestBg = Color(0xFFDCE8DF),
    draftBg = Color(0xFFFFE7A3), draftInk = Color(0xFF3D2E00), chipBg = Color(0xFFE6E1D5), chipInk = Color(0xFF3B3F3A),
    accentText = Color(0xFFB83A0B), activePill = Color(0xFFFFE2D4),
)

val DarkFm = FmColors(
    dark = true,
    bg = Color(0xFF000000), surface = Color(0xFF111311), ink = Color(0xFFF2EFE8), muted = Color(0xFFA9ADA3),
    rule = Color(0xFF2A2D29), nav = Color(0xFF0B0C0B), forest = Color(0xFF8FD1AE), forestBg = Color(0xFF16261E),
    draftBg = Color(0xFF3A2E00), draftInk = Color(0xFFFFD966), chipBg = Color(0xFF1F221F), chipInk = Color(0xFFC9CCC3),
    accentText = Color(0xFFFF7A45), activePill = Color(0xFF2B1408),
)

val LocalFm = staticCompositionLocalOf { LightFm }

/** Shortcut for the current colors: `fm.orange`. */
val fm: FmColors @Composable get() = LocalFm.current

private fun scheme(c: FmColors) = if (c.dark) darkColorScheme(
    primary = c.accentText, onPrimary = c.onOrange, primaryContainer = c.orange, onPrimaryContainer = c.onOrange,
    secondary = c.forest, onSecondary = Color.Black, secondaryContainer = c.forestBg, onSecondaryContainer = c.ink,
    tertiaryContainer = c.draftBg, onTertiaryContainer = c.draftInk,
    background = c.bg, onBackground = c.ink, surface = c.bg, onSurface = c.ink,
    surfaceVariant = c.surface, onSurfaceVariant = c.muted, surfaceContainerLowest = c.bg,
    surfaceContainerLow = c.surface, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
    surfaceContainerHighest = c.chipBg, surfaceBright = c.surface, surfaceDim = c.bg,
    outline = c.muted, outlineVariant = c.rule, error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = c.emergency, onErrorContainer = c.onEmergency, scrim = Color.Black,
    inverseSurface = c.ink, inverseOnSurface = c.bg,
) else lightColorScheme(
    primary = c.accentText, onPrimary = Color.White, primaryContainer = c.orange, onPrimaryContainer = c.onOrange,
    secondary = c.forest, onSecondary = Color.White, secondaryContainer = c.forestBg, onSecondaryContainer = c.ink,
    tertiaryContainer = c.draftBg, onTertiaryContainer = c.draftInk,
    background = c.bg, onBackground = c.ink, surface = c.bg, onSurface = c.ink,
    surfaceVariant = c.chipBg, onSurfaceVariant = c.muted, surfaceContainerLowest = c.surface,
    surfaceContainerLow = c.nav, surfaceContainer = c.nav, surfaceContainerHigh = c.surface,
    surfaceContainerHighest = c.chipBg, surfaceBright = c.surface, surfaceDim = c.rule,
    outline = c.muted, outlineVariant = c.rule, error = c.emergency, onError = Color.White,
    errorContainer = c.emergency, onErrorContainer = c.onEmergency, scrim = Color.Black,
    inverseSurface = c.ink, inverseOnSurface = c.bg,
)

private val Type = Typography(
    displayLarge = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold, fontSize = 96.sp, lineHeight = 86.sp),
    displaySmall = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold, fontSize = 38.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = BarlowCondensed, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.08.em),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.04.em),
)

/** [dark] forces a theme (step-by-step mode is always dark); null follows the system setting. */
@Composable
fun FallbackTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val c = if (dark ?: isSystemInDarkTheme()) DarkFm else LightFm
    CompositionLocalProvider(LocalFm provides c) {
        MaterialTheme(colorScheme = scheme(c), typography = Type, content = content)
    }
}
