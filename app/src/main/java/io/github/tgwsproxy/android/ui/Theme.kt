package io.github.tgwsproxy.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val TelegramLightColorScheme = lightColorScheme(
    primary = Color(0xFF0077B6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCAE9FF),
    onPrimaryContainer = Color(0xFF001E30),
    secondary = Color(0xFF2B6CB0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E4FF),
    onSecondaryContainer = Color(0xFF0A1E3D),
    tertiary = Color(0xFF00875A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC5F4DF),
    onTertiaryContainer = Color(0xFF002114),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF151B23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF151B23),
    surfaceVariant = Color(0xFFE7EDF4),
    onSurfaceVariant = Color(0xFF424E5E),
    outline = Color(0xFF748194),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val TelegramDarkColorScheme = darkColorScheme(
    primary = Color(0xFF66BFFF),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF004B70),
    onPrimaryContainer = Color(0xFFCAE9FF),
    secondary = Color(0xFF9AC5FF),
    onSecondary = Color(0xFF00315E),
    secondaryContainer = Color(0xFF14477C),
    onSecondaryContainer = Color(0xFFD6E4FF),
    tertiary = Color(0xFF6DD9A8),
    onTertiary = Color(0xFF003824),
    tertiaryContainer = Color(0xFF005236),
    onTertiaryContainer = Color(0xFFC5F4DF),
    background = Color(0xFF0E141B),
    onBackground = Color(0xFFE3E8EF),
    surface = Color(0xFF151D27),
    onSurface = Color(0xFFE3E8EF),
    surfaceVariant = Color(0xFF212C3A),
    onSurfaceVariant = Color(0xFF9FB0C4),
    outline = Color(0xFF68788B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun TgWsProxyTheme(
    themeMode: String,
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
        if (useDark) TelegramDarkColorScheme else TelegramLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
