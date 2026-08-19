package com.stocktracker.core.designsystem.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.stocktracker.core.designsystem.ComponentRadius
import com.stocktracker.core.designsystem.Spacing

/**
 * Shared selectable pill chip — replaces the near-identical private `GroupToggleButton`
 * (PortfolioPieChartsCard.kt) and `ViewToggleButton` (PortfolioPnlChartCard.kt), which were
 * plain text buttons; this version fills with the primary container when active so the
 * selected option is visible at a glance rather than only by text color.
 */
@Composable
fun ToggleChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(ComponentRadius.chip),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
