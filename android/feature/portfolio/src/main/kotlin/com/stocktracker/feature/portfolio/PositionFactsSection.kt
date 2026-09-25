package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.stocktracker.core.calc.positionFacts
import com.stocktracker.core.designsystem.Spacing
import com.stocktracker.core.model.PortfolioRow

/**
 * The web table's per-position columns — quantity, average buy, first buy, lots, type,
 * return %, brokers, ISIN — as a second grid under the money figures on the detail screen.
 */
@Composable
internal fun PositionFactsSection(row: PortfolioRow, displayCurrency: String, rates: Map<String, Double>, modifier: Modifier = Modifier) {
    val facts = positionFacts(row)
    val avgBuy = com.stocktracker.core.calc.convert(facts.avgBuyPrice, facts.avgBuyCurrency, displayCurrency, rates)
    Column(modifier) {
        DetailMetricGrid(
            modifier = Modifier.fillMaxWidth(),
            cells = listOf(
                DetailMetric(
                    "Quantity", formatQty(facts.quantityHeld),
                    sub = if (facts.quantitySold > 0) "bought ${formatQty(facts.quantityBought)} · sold ${formatQty(facts.quantitySold)}" else null,
                ),
                DetailMetric("Avg buy", "${formatMoney(avgBuy)} $displayCurrency"),
                DetailMetric("First buy", formatDisplayDate(facts.firstBuyDate)),
                DetailMetric("Lots", facts.lots.toString()),
                DetailMetric("Type", typeBadgeLabel(row.type), color = typeBadgeColor(row.type)),
                DetailMetric(
                    "Return %", facts.returnPercent?.let(::signedPercent) ?: "—",
                    color = facts.returnPercent?.let { pnlColorRounded(it) },
                ),
            ),
        )
        if (facts.brokers.isNotEmpty()) FactLine(if (facts.brokers.size > 1) "Brokers" else "Broker", facts.brokers.joinToString(", "))
        facts.isin?.let { FactLine("ISIN", it, monospace = true) }
    }
}

@Composable
private fun FactLine(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.sm, start = Spacing.xs, end = Spacing.xs)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = Spacing.md),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
