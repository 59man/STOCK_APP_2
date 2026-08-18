package com.stocktracker.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Positive/negative P&L colors — shared by every screen so gain/loss reads consistently. */
object StockTrackerColors {
    val Gain = Color(0xFF34D566)
    val Loss = Color(0xFFFF5449)
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

private val StockTrackerLightScheme = lightColorScheme(
    primary = Color(0xFF5B4FCF),
)

@Composable
fun StockTrackerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) StockTrackerDarkScheme else StockTrackerLightScheme
    MaterialTheme(colorScheme = colorScheme, content = content)
}
