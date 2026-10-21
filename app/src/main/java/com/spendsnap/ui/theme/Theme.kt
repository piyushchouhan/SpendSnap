package com.spendsnap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.spendsnap.ThemeMode
import com.spendsnap.container

// Tokens from the "SpendSnap Android prototype design" (claude.ai/design).
private val Light = lightColorScheme(
    primary = Color(0xFF2B6A46), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB0F1C3), onPrimaryContainer = Color(0xFF00210F),
    secondaryContainer = Color(0xFFD2E8D4), onSecondaryContainer = Color(0xFF0C1F13),
    tertiary = Color(0xFF3A646F), tertiaryContainer = Color(0xFFBEEAF6), onTertiaryContainer = Color(0xFF001F26),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF4), onBackground = Color(0xFF171D19),
    surface = Color(0xFFF6FBF4), onSurface = Color(0xFF171D19), onSurfaceVariant = Color(0xFF414942),
    outline = Color(0xFF717971), outlineVariant = Color(0xFFC1C9C0),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F5EE),
    surfaceContainer = Color(0xFFEAEFE8), surfaceContainerHigh = Color(0xFFE5EAE3),
    surfaceContainerHighest = Color(0xFFDFE4DD),
    inverseSurface = Color(0xFF2C322D), inverseOnSurface = Color(0xFFEDF2EB), inversePrimary = Color(0xFF95D5A8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF95D5A8), onPrimary = Color(0xFF00391C),
    primaryContainer = Color(0xFF0F5130), onPrimaryContainer = Color(0xFFB0F1C3),
    secondaryContainer = Color(0xFF384B3C), onSecondaryContainer = Color(0xFFD2E8D4),
    tertiary = Color(0xFFA2CEDA), tertiaryContainer = Color(0xFF214C57), onTertiaryContainer = Color(0xFFBEEAF6),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1511), onBackground = Color(0xFFDFE4DD),
    surface = Color(0xFF0F1511), onSurface = Color(0xFFDFE4DD), onSurfaceVariant = Color(0xFFC1C9C0),
    outline = Color(0xFF8B938A), outlineVariant = Color(0xFF414942),
    surfaceContainerLowest = Color(0xFF0A0F0C), surfaceContainerLow = Color(0xFF171D19),
    surfaceContainer = Color(0xFF1B211D), surfaceContainerHigh = Color(0xFF252B27),
    surfaceContainerHighest = Color(0xFF303632),
    inverseSurface = Color(0xFFDFE4DD), inverseOnSurface = Color(0xFF2C322D), inversePrimary = Color(0xFF2B6A46),
)

/** Design colors that Material 3 has no slot for: warning and per-app avatar tints. */
@Immutable
data class ExtraColors(
    val warningContainer: Color,
    val onWarningContainer: Color,
    val googlePay: Color,
    val onGooglePay: Color,
    val phonePe: Color,
    val onPhonePe: Color,
)

private val LightExtra = ExtraColors(
    Color(0xFFFFDEA6), Color(0xFF271900), Color(0xFFD8E2FF), Color(0xFF001A41), Color(0xFFEBDDFF), Color(0xFF250059),
)
private val DarkExtra = ExtraColors(
    Color(0xFF5E4200), Color(0xFFFFDEA6), Color(0xFF2B4678), Color(0xFFD8E2FF), Color(0xFF4F378A), Color(0xFFEBDDFF),
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

@Composable
fun SpendSnapTheme(content: @Composable () -> Unit) {
    val mode by LocalContext.current.container.settings.theme.collectAsState()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
