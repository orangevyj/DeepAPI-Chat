package com.orangestudio.deepapichat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.orangestudio.deepapichat.data.model.AccentColor
import com.orangestudio.deepapichat.data.model.ThemeMode

/**
 * The four tones each accent contributes: a pair for the light scheme and a pair
 * for the dark one (a mid tone for the accent itself, a tonal container for
 * filled surfaces).
 */
private data class AccentTones(
    val light: Color,
    val lightContainer: Color,
    val dark: Color,
    val darkContainer: Color,
)

private fun tonesFor(accent: AccentColor): AccentTones = when (accent) {
    AccentColor.Default -> AccentTones(Color(0xFF6750A4), Color(0xFFEADDFF), Color(0xFFD0BCFF), Color(0xFF4F378B))
    AccentColor.Blue -> AccentTones(Color(0xFF0B57D0), Color(0xFFD3E3FD), Color(0xFFA8C7FA), Color(0xFF0842A0))
    AccentColor.Teal -> AccentTones(Color(0xFF00696E), Color(0xFF9EE7EE), Color(0xFF4DD0E1), Color(0xFF004A4F))
    AccentColor.Green -> AccentTones(Color(0xFF386A20), Color(0xFFC8F0B0), Color(0xFF9CD67D), Color(0xFF25500F))
    AccentColor.Amber -> AccentTones(Color(0xFF7A5900), Color(0xFFFFE08A), Color(0xFFFFD54F), Color(0xFF5C4300))
    AccentColor.Orange -> AccentTones(Color(0xFF8B5000), Color(0xFFFFDCC0), Color(0xFFFFB77A), Color(0xFF663D00))
    AccentColor.Red -> AccentTones(Color(0xFFB3261E), Color(0xFFF9DEDC), Color(0xFFF2B8B5), Color(0xFF8C1D18))
    AccentColor.Violet -> AccentTones(Color(0xFF7B1FA2), Color(0xFFEADDFF), Color(0xFFCE93D8), Color(0xFF4A0072))
}

/** Black or near-black text, whichever stays readable on [background]. */
private fun onColor(background: Color): Color =
    if (background.luminance() > 0.5f) Color(0xFF1C1B1F) else Color.White

/** The accent itself, as a swatch: the dark tone in dark schemes, else the light one. */
fun accentSwatch(accent: AccentColor, dark: Boolean): Color =
    tonesFor(accent).let { if (dark) it.dark else it.light }

/** A legible tick colour to draw on top of [accentSwatch]. */
fun onAccentSwatch(accent: AccentColor, dark: Boolean): Color = onColor(accentSwatch(accent, dark))

/** True when [mode] resolves to the dark scheme on this device right now. */
@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * Builds the full scheme for an accent. In the dark scheme, [amoled] swaps the
 * background and every surface container for pure or near black, which is what
 * an OLED panel actually saves power on.
 */
fun colorSchemeFor(accent: AccentColor, dark: Boolean, amoled: Boolean): ColorScheme {
    val tones = tonesFor(accent)
    if (!dark) {
        return lightColorScheme(
            primary = tones.light,
            onPrimary = onColor(tones.light),
            primaryContainer = tones.lightContainer,
            onPrimaryContainer = onColor(tones.lightContainer),
            secondary = tones.light,
            onSecondary = onColor(tones.light),
            secondaryContainer = tones.lightContainer,
            onSecondaryContainer = onColor(tones.lightContainer),
            tertiary = tones.light,
            onTertiary = onColor(tones.light),
            background = Color(0xFFFFFBFE),
            onBackground = Color(0xFF1C1B1F),
            surface = Color(0xFFFFFBFE),
            onSurface = Color(0xFF1C1B1F),
            surfaceVariant = Color(0xFFE7E0EC),
            onSurfaceVariant = Color(0xFF49454F),
            surfaceTint = tones.light,
            inversePrimary = tones.dark,
        )
    }

    val background = if (amoled) Color.Black else Color(0xFF141218)
    return darkColorScheme(
        primary = tones.dark,
        onPrimary = onColor(tones.dark),
        primaryContainer = tones.darkContainer,
        onPrimaryContainer = onColor(tones.darkContainer),
        secondary = tones.dark,
        onSecondary = onColor(tones.dark),
        secondaryContainer = tones.darkContainer,
        onSecondaryContainer = onColor(tones.darkContainer),
        tertiary = tones.dark,
        onTertiary = onColor(tones.dark),
        background = background,
        onBackground = Color(0xFFE6E1E5),
        surface = background,
        onSurface = Color(0xFFE6E1E5),
        surfaceVariant = if (amoled) Color(0xFF121212) else Color(0xFF2B2930),
        onSurfaceVariant = Color(0xFFCAC4D0),
        surfaceTint = tones.dark,
        outline = Color(0xFF938F99),
        outlineVariant = if (amoled) Color(0xFF2A2A2A) else Color(0xFF49454F),
        surfaceDim = background,
        surfaceBright = if (amoled) Color(0xFF1E1E1E) else Color(0xFF38343D),
        surfaceContainerLowest = if (amoled) Color.Black else Color(0xFF0F0D13),
        surfaceContainerLow = if (amoled) Color(0xFF070707) else Color(0xFF1D1B20),
        surfaceContainer = if (amoled) Color(0xFF0C0C0C) else Color(0xFF211F26),
        surfaceContainerHigh = if (amoled) Color(0xFF121212) else Color(0xFF2B2930),
        surfaceContainerHighest = if (amoled) Color(0xFF191919) else Color(0xFF36343B),
        inverseSurface = Color(0xFFE6E1E5),
        inverseOnSurface = Color(0xFF313033),
    )
}

@Composable
fun DeepAPIChatTheme(
    themeMode: ThemeMode = ThemeMode.System,
    amoledBlack: Boolean = false,
    accent: AccentColor = AccentColor.Default,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode)
    MaterialTheme(
        colorScheme = colorSchemeFor(accent, dark, amoledBlack && dark),
        typography = Typography,
        content = content,
    )
}

/** Keeps the system-bar icons legible against the current scheme. */
@Composable
fun SystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val context = view.context
    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
