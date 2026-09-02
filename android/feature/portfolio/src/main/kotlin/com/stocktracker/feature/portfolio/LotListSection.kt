package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.stocktracker.core.designsystem.NumericTypography
import com.stocktracker.core.designsystem.Spacing
import com.stocktracker.core.designsystem.StockTrackerColors
import com.stocktracker.core.designsystem.components.AppCard
import com.stocktracker.core.designsystem.components.Badge
import com.stocktracker.core.model.Position
import java.util.Locale

/** Card-per-lot list — replaces the old fixed-column grid table, whose Price column wrapped
 * money values across multiple lines because it had no room to grow. */
@Composable
internal fun LotListSection(positions: List<Position>, onEdit: (Position) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        positions.forEach { lot -> LotCard(lot = lot, onEdit = { onEdit(lot) }) }
    }
}

@Composable
internal fun LotCard(lot: Position, onEdit: () -> Unit) {
    val isSold = lot.sellDate != null && lot.sellPrice != null
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(lot.buyDate, style = NumericTypography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Badge(
                if (isSold) "Sold" else "Open",
                containerColor = if (isSold) MaterialTheme.colorScheme.surfaceVariant else StockTrackerColors.gain.copy(alpha = 0.16f),
                contentColor = if (isSold) MaterialTheme.colorScheme.onSurfaceVariant else StockTrackerColors.gain,
            )
        }
        LotField("Quantity", formatQty(lot.quantity))
        LotField("Buy price", "${formatMoney(lot.buyPrice)} ${lot.currency}")
        lot.broker?.let { LotField("Broker", it) }
        if (isSold) {
            LotField("Sell date", lot.sellDate!!)
            LotField("Sell price", "${formatMoney(lot.sellPrice!!)} ${lot.currency}")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onEdit) { Text("Edit") }
        }
    }
}

@Composable
private fun LotField(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = NumericTypography.labelMedium, maxLines = 1)
    }
}

private fun formatQty(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale.US, "%.4f", value)
