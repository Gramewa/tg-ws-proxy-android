package io.github.tgwsproxy.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Immutable
data class PaletteOption(
    val id: String,
    val previewColor: Color,
)

val AvailablePalettes = listOf(
    PaletteOption("ocean", Color(0xFF0088CC)),
    PaletteOption("emerald", Color(0xFF059669)),
    PaletteOption("amethyst", Color(0xFF7C3AED)),
    PaletteOption("sunset", Color(0xFFEA580C)),
)

private val OceanLight = lightColorScheme(
    primary = Color(0xFF0077B6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCAE9FF),
    onPrimaryContainer = Color(0xFF001E30),
    secondary = Color(0xFF0096C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6F0FF),
    onSecondaryContainer = Color(0xFF00273B),
    tertiary = Color(0xFF00A878),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC2F7E4),
    onTertiaryContainer = Color(0xFF002116),
    background = Color(0xFFF3F7FC),
    onBackground = Color(0xFF101824),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101824),
    surfaceVariant = Color(0xFFE4ECF5),
    onSurfaceVariant = Color(0xFF425066),
    outline = Color(0xFF73829A),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val OceanDark = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF002A42),
    primaryContainer = Color(0xFF06466B),
    onPrimaryContainer = Color(0xFFCAE9FF),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF002952),
    secondaryContainer = Color(0xFF123C69),
    onSecondaryContainer = Color(0xFFD6E6FF),
    tertiary = Color(0xFF2FE6A6),
    onTertiary = Color(0xFF003824),
    tertiaryContainer = Color(0xFF005538),
    onTertiaryContainer = Color(0xFFC2F7E4),
    background = Color(0xFF09111C),
    onBackground = Color(0xFFE5ECF6),
    surface = Color(0xFF111C2B),
    onSurface = Color(0xFFE5ECF6),
    surfaceVariant = Color(0xFF1B293D),
    onSurfaceVariant = Color(0xFF9FB2C9),
    outline = Color(0xFF63768F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val EmeraldLight = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F7E5),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF0D9488),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF0284C7),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD0EEFF),
    onTertiaryContainer = Color(0xFF001E30),
    background = Color(0xFFF3FAF7),
    onBackground = Color(0xFF0E1D17),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0E1D17),
    surfaceVariant = Color(0xFFE1F0EA),
    onSurfaceVariant = Color(0xFF3E554C),
    outline = Color(0xFF6C847B),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val EmeraldDark = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005437),
    onPrimaryContainer = Color(0xFFC8F7E5),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFF38BDF8),
    onTertiary = Color(0xFF002E45),
    tertiaryContainer = Color(0xFF00486B),
    onTertiaryContainer = Color(0xFFD0EEFF),
    background = Color(0xFF08140F),
    onBackground = Color(0xFFE2F1EA),
    surface = Color(0xFF102019),
    onSurface = Color(0xFFE2F1EA),
    surfaceVariant = Color(0xFF1A3127),
    onSurfaceVariant = Color(0xFF99B8AA),
    outline = Color(0xFF5D7A6E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AmethystLight = lightColorScheme(
    primary = Color(0xFF6D28D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DDFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2EEFF),
    onSecondaryContainer = Color(0xFF001E30),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC7F6E3),
    onTertiaryContainer = Color(0xFF002116),
    background = Color(0xFFF6F4FC),
    onBackground = Color(0xFF181426),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF181426),
    surfaceVariant = Color(0xFFEAE5F5),
    onSurfaceVariant = Color(0xFF4C4561),
    outline = Color(0xFF7B7392),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val AmethystDark = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF2E0B70),
    primaryContainer = Color(0xFF482392),
    onPrimaryContainer = Color(0xFFE8DDFF),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF002D45),
    secondaryContainer = Color(0xFF05476B),
    onSecondaryContainer = Color(0xFFD2EEFF),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF003824),
    tertiaryContainer = Color(0xFF005437),
    onTertiaryContainer = Color(0xFFC7F6E3),
    background = Color(0xFF0E0B1A),
    onBackground = Color(0xFFEAE6F7),
    surface = Color(0xFF17132A),
    onSurface = Color(0xFFEAE6F7),
    surfaceVariant = Color(0xFF241E3D),
    onSurfaceVariant = Color(0xFFAFA7C8),
    outline = Color(0xFF70678B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val SunsetLight = lightColorScheme(
    primary = Color(0xFFD9480F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0D3),
    onPrimaryContainer = Color(0xFF3B0E00),
    secondary = Color(0xFFD97706),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE8C2),
    onSecondaryContainer = Color(0xFF331B00),
    tertiary = Color(0xFF0D9488),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8F7F1),
    onTertiaryContainer = Color(0xFF00201C),
    background = Color(0xFFFCF7F4),
    onBackground = Color(0xFF221815),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF221815),
    surfaceVariant = Color(0xFFF3E7E1),
    onSurfaceVariant = Color(0xFF5A4942),
    outline = Color(0xFF8A766E),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val SunsetDark = darkColorScheme(
    primary = Color(0xFFFF8A5B),
    onPrimary = Color(0xFF4D1500),
    primaryContainer = Color(0xFF7C2705),
    onPrimaryContainer = Color(0xFFFFE0D3),
    secondary = Color(0xFFFBBF24),
    onSecondary = Color(0xFF3D2400),
    secondaryContainer = Color(0xFF633E00),
    onSecondaryContainer = Color(0xFFFFE8C2),
    tertiary = Color(0xFF2DD4BF),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005248),
    onTertiaryContainer = Color(0xFFC8F7F1),
    background = Color(0xFF150F0E),
    onBackground = Color(0xFFF5E9E4),
    surface = Color(0xFF211716),
    onSurface = Color(0xFFF5E9E4),
    surfaceVariant = Color(0xFF312220),
    onSurfaceVariant = Color(0xFFC4ACA3),
    outline = Color(0xFF846D65),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Immutable
data class HeroVisualPalette(
    val orbGradient: List<Color>,
    val ringColor: Color,
    val glowColor: Color,
    val upAccent: Color,
    val downAccent: Color,
)

@Composable
fun rememberHeroPalette(isRunning: Boolean, isTransitioning: Boolean): HeroVisualPalette {
    val cs = MaterialTheme.colorScheme
    return when {
        isTransitioning -> HeroVisualPalette(
            orbGradient = listOf(cs.secondary, cs.primary),
            ringColor = cs.secondary,
            glowColor = cs.secondary.copy(alpha = 0.28f),
            upAccent = cs.primary,
            downAccent = cs.tertiary,
        )
        isRunning -> HeroVisualPalette(
            orbGradient = listOf(cs.tertiary, cs.primary),
            ringColor = cs.tertiary,
            glowColor = cs.tertiary.copy(alpha = 0.34f),
            upAccent = cs.primary,
            downAccent = cs.tertiary,
        )
        else -> HeroVisualPalette(
            orbGradient = listOf(cs.surfaceVariant, cs.outline.copy(alpha = 0.45f)),
            ringColor = cs.outline.copy(alpha = 0.55f),
            glowColor = cs.primary.copy(alpha = 0.12f),
            upAccent = cs.primary,
            downAccent = cs.tertiary,
        )
    }
}

private fun schemeFor(palette: String, dark: Boolean): ColorScheme {
    return when (palette) {
        "emerald" -> if (dark) EmeraldDark else EmeraldLight
        "amethyst" -> if (dark) AmethystDark else AmethystLight
        "sunset" -> if (dark) SunsetDark else SunsetLight
        else -> if (dark) OceanDark else OceanLight
    }
}

@Composable
fun TgWsProxyTheme(
    themeMode: String,
    colorPalette: String,
    dynamicColors: Boolean,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }

    val context = LocalContext.current
    val colorScheme = if (dynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        schemeFor(colorPalette, useDark)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
