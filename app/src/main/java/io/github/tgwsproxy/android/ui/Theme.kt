package io.github.tgwsproxy.android.ui

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Immutable
data class PaletteOption(
    val id: String,
    val previewColor: Color,
)

val AvailablePalettes = listOf(
    PaletteOption("obsidian", Color(0xFF22D3EE)),
    PaletteOption("ocean", Color(0xFF0284C7)),
    PaletteOption("emerald", Color(0xFF10B981)),
    PaletteOption("amethyst", Color(0xFF8B5CF6)),
    PaletteOption("sunset", Color(0xFFF97316)),
)

private val ObsidianLight = lightColorScheme(
    primary = Color(0xFF0E7490),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFFAFE),
    onPrimaryContainer = Color(0xFF002028),
    secondary = Color(0xFF4F46E5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF16134A),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF002116),
    background = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF64748B),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF450A0A),
)

private val ObsidianDark = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF002833),
    primaryContainer = Color(0xFF0B3B4F),
    onPrimaryContainer = Color(0xFFCFFAFE),
    secondary = Color(0xFF818CF8),
    onSecondary = Color(0xFF16134A),
    secondaryContainer = Color(0xFF2B2870),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Color(0xFF10B981),
    onTertiary = Color(0xFF00291B),
    tertiaryContainer = Color(0xFF064E3B),
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = Color(0xFF060B14),
    onBackground = Color(0xFFE8F0FC),
    surface = Color(0xFF0D1626),
    onSurface = Color(0xFFE8F0FC),
    surfaceVariant = Color(0xFF162338),
    onSurfaceVariant = Color(0xFF94A7C2),
    outline = Color(0xFF3A5070),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF5C0A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
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
            glowColor = cs.secondary.copy(alpha = 0.34f),
            upAccent = cs.primary,
            downAccent = cs.tertiary,
        )
        isRunning -> HeroVisualPalette(
            orbGradient = listOf(cs.tertiary, cs.primary),
            ringColor = cs.tertiary,
            glowColor = cs.tertiary.copy(alpha = 0.40f),
            upAccent = cs.primary,
            downAccent = cs.tertiary,
        )
        else -> HeroVisualPalette(
            orbGradient = listOf(cs.primary.copy(alpha = 0.22f), cs.surfaceVariant),
            ringColor = cs.primary.copy(alpha = 0.55f),
            glowColor = cs.primary.copy(alpha = 0.16f),
            upAccent = cs.primary,
            downAccent = cs.tertiary,
        )
    }
}

@Composable
fun AmbientAuroraBackground(
    isRunning: Boolean,
    isTransitioning: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.5f

    val targetTop = when {
        isTransitioning -> cs.secondary
        isRunning -> cs.tertiary
        else -> cs.primary
    }
    val targetBottom = if (isRunning) cs.primary else cs.secondary

    val topGlow by animateColorAsState(targetTop, tween(600), label = "auroraTop")
    val bottomGlow by animateColorAsState(targetBottom, tween(600), label = "auroraBottom")

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = cs.background)

            val topAlpha = if (isDark) {
                if (isRunning) 0.24f else 0.14f
            } else {
                if (isRunning) 0.14f else 0.08f
            }
            val topCenter = Offset(size.width * 0.5f, size.height * 0.18f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(topGlow.copy(alpha = topAlpha), Color.Transparent),
                    center = topCenter,
                    radius = size.maxDimension * 0.55f,
                ),
                center = topCenter,
                radius = size.maxDimension * 0.55f,
            )

            val bottomAlpha = if (isDark) 0.14f else 0.07f
            val bottomCenter = Offset(size.width * 0.88f, size.height * 0.86f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(bottomGlow.copy(alpha = bottomAlpha), Color.Transparent),
                    center = bottomCenter,
                    radius = size.maxDimension * 0.48f,
                ),
                center = bottomCenter,
                radius = size.maxDimension * 0.48f,
            )

            // Subtle polar telemetry rings
            val gridCenter = Offset(size.width * 0.5f, size.height * 0.24f)
            val ringStroke = Stroke(width = 1.dp.toPx())
            val gridAlpha = if (isDark) 0.055f else 0.045f
            for (mult in listOf(0.28f, 0.46f, 0.68f)) {
                drawCircle(
                    color = topGlow.copy(alpha = gridAlpha),
                    radius = size.minDimension * mult,
                    center = gridCenter,
                    style = ringStroke,
                )
            }
        }
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accentColor: Color? = null,
    shape: Shape = RoundedCornerShape(22.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.background.luminance() < 0.5f
    val borderAccent = accentColor ?: cs.primary
    val containerColor = if (accentColor != null) {
        accentColor.copy(alpha = if (isDark) 0.13f else 0.09f)
    } else {
        cs.surface.copy(alpha = if (isDark) 0.78f else 0.88f)
    }
    val borderBrush = Brush.linearGradient(
        colors = listOf(
            borderAccent.copy(alpha = if (accentColor != null) 0.46f else if (isDark) 0.28f else 0.20f),
            cs.outline.copy(alpha = if (isDark) 0.20f else 0.14f),
            borderAccent.copy(alpha = if (isDark) 0.08f else 0.05f),
        ),
    )

    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        contentColor = cs.onSurface,
        border = BorderStroke(1.dp, borderBrush),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

private fun schemeFor(palette: String, dark: Boolean): ColorScheme {
    return when (palette) {
        "ocean" -> if (dark) OceanDark else OceanLight
        "emerald" -> if (dark) EmeraldDark else EmeraldLight
        "amethyst" -> if (dark) AmethystDark else AmethystLight
        "sunset" -> if (dark) SunsetDark else SunsetLight
        else -> if (dark) ObsidianDark else ObsidianLight
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

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !useDark
            controller.isAppearanceLightNavigationBars = !useDark
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
            content()
        }
    }
}
