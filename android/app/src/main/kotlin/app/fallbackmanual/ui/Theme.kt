package app.fallbackmanual.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Forest greens and earth tones; red is reserved for emergencies and warnings.
private val Light = lightColorScheme(
    primary = Color(0xFF2F5D3A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E8D2),
    onPrimaryContainer = Color(0xFF0E2614),
    secondary = Color(0xFF6B5B43),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE3D2),
    onSecondaryContainer = Color(0xFF261C0C),
    background = Color(0xFFF6F4EE),
    onBackground = Color(0xFF1A1C18),
    surface = Color(0xFFF6F4EE),
    onSurface = Color(0xFF1A1C18),
    surfaceVariant = Color(0xFFE3E4DA),
    onSurfaceVariant = Color(0xFF43483F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F0E9),
    surfaceContainer = Color(0xFFECEBE3),
    surfaceContainerHigh = Color(0xFFE6E5DD),
    surfaceContainerHighest = Color(0xFFE0DFD7),
    surfaceBright = Color(0xFFF6F4EE),
    surfaceDim = Color(0xFFD9D8D0),
    inverseSurface = Color(0xFF2F312C),
    inverseOnSurface = Color(0xFFF1F1EA),
    scrim = Color(0xFF000000),
    outline = Color(0xFF73796E),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFB3261E),
    onErrorContainer = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE08A),
    onTertiaryContainer = Color(0xFF261A00),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CCFA4),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF1F4A2B),
    onPrimaryContainer = Color(0xFFD3E8D2),
    secondary = Color(0xFFD6C4A8),
    onSecondary = Color(0xFF3B2F1C),
    secondaryContainer = Color(0xFF524430),
    onSecondaryContainer = Color(0xFFEDE3D2),
    background = Color(0xFF131712),
    onBackground = Color(0xFFE2E3DC),
    surface = Color(0xFF131712),
    onSurface = Color(0xFFE2E3DC),
    surfaceVariant = Color(0xFF43483F),
    onSurfaceVariant = Color(0xFFC3C8BC),
    surfaceContainerLowest = Color(0xFF0E120D),
    surfaceContainerLow = Color(0xFF1A1E19),
    surfaceContainer = Color(0xFF1D221C),
    surfaceContainerHigh = Color(0xFF272C26),
    surfaceContainerHighest = Color(0xFF323731),
    surfaceBright = Color(0xFF383D37),
    surfaceDim = Color(0xFF131712),
    inverseSurface = Color(0xFFE2E3DC),
    inverseOnSurface = Color(0xFF2F312C),
    scrim = Color(0xFF000000),
    outline = Color(0xFF8D9387),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    tertiaryContainer = Color(0xFF5C4700),
    onTertiaryContainer = Color(0xFFFFE08A),
)

private val Type = Typography().let { t ->
    t.copy(
        bodyLarge = t.bodyLarge.copy(fontSize = 18.sp, lineHeight = 27.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 16.sp, lineHeight = 23.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    )
}

@Composable
fun FallbackTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
