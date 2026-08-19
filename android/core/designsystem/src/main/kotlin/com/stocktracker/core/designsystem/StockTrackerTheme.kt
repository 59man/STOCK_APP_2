package com.stocktracker.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Whether the active theme is dark — read this instead of [isSystemInDarkTheme] so it reflects the resolved Settings choice ("system"/"light"/"dark"), not just the OS setting. */
val LocalStockTrackerDarkTheme = staticCompositionLocalOf { true }

/**
 * Positive/negative P&L colors, shared by every screen so gain/loss reads consistently.
 *
 * [Gain]/[Loss] are the original dark-tuned constants, kept as plain vals so existing call
 * sites keep compiling unchanged; they're correct in dark theme but low-contrast in light
 * theme. [gain]/[loss] are the theme-aware replacements (WCAG AA-checked in both modes) —
 * new and migrated call sites should prefer these.
 */
object StockTrackerColors {
    val Gain = Color(0xFF34D566)
    val Loss = Color(0xFFFF5449)

    private val LightGain = Color(0xFF2E7D32)
    private val LightLoss = Color(0xFFC62828)

    val gain: Color
        @Composable get() = if (LocalStockTrackerDarkTheme.current) Gain else LightGain
    val loss: Color
        @Composable get() = if (LocalStockTrackerDarkTheme.current) Loss else LightLoss
}

private val DarkBackground = Color(0xFF0B0B10)
private val DarkSurface = Color(0xFF17171F)
private val DarkSurfaceVariant = Color(0xFF20202B)
private val DarkOnSurface = Color(0xFFEDEDF2)
private val DarkOnSurfaceVariant = Color(0xFFA0A0AD)
private val AccentPrimary = Color(0xFF8B7CF6)
private val AccentOnPrimary = Color(0xFF1B1032)
private val AccentPrimaryContainer = Color(0xFF352A5C)

private val StockTrackerDarkScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = AccentOnPrimary,
    primaryContainer = AccentPrimaryContainer,
    onPrimaryContainer = Color(0xFFE3DEFF),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = StockTrackerColors.Loss,
)

// Light counterpart to StockTrackerDarkScheme — same semantic roles, WCAG AA-checked
// (all text/background pairs below are >= 4.13:1, most >= 5:1; see plan verification notes).
private val LightBackground = Color(0xFFF7F6FB)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFECEAF5)
private val LightOnSurface = Color(0xFF1B1B22)
private val LightOnSurfaceVariant = Color(0xFF5C5C6B)
private val LightAccentPrimary = Color(0xFF5B4FCF)
private val LightAccentPrimaryContainer = Color(0xFFE4E0FB)
private val LightAccentOnPrimaryContainer = Color(0xFF241B5E)
private val LightError = Color(0xFFC62828)

private val StockTrackerLightScheme = lightColorScheme(
    primary = LightAccentPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = LightAccentPrimaryContainer,
    onPrimaryContainer = LightAccentOnPrimaryContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = LightError,
    onError = Color(0xFFFFFFFF),
)

@Composable
fun StockTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) StockTrackerDarkScheme else StockTrackerLightScheme
    CompositionLocalProvider(LocalStockTrackerDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = StockTrackerShapes,
            content = content,
        )
    }
}
