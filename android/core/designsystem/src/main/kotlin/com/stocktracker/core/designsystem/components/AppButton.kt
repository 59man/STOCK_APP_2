package com.stocktracker.core.designsystem.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.stocktracker.core.designsystem.ComponentRadius
import com.stocktracker.core.designsystem.StockTrackerColors

/** Semantic intent for [AppButton] — maps to a theme-aware content color instead of a per-call-site hardcoded one. */
enum class AppButtonVariant { Primary, Secondary, Danger }

/** Shared text-button shell — replaces ad-hoc `TextButton(colors = ButtonDefaults.textButtonColors(contentColor = ...))` call sites. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Secondary,
    emphasized: Boolean = false,
) {
    val contentColor = when (variant) {
        AppButtonVariant.Primary -> MaterialTheme.colorScheme.primary
        AppButtonVariant.Secondary -> MaterialTheme.colorScheme.onSurfaceVariant
        AppButtonVariant.Danger -> StockTrackerColors.loss
    }
    TextButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(ComponentRadius.button),
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
    ) {
        Text(text, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal)
    }
}
