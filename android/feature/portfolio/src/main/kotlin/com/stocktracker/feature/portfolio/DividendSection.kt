package com.stocktracker.feature.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.stocktracker.core.designsystem.NumericTypography
import com.stocktracker.core.designsystem.Spacing
import com.stocktracker.core.designsystem.StockTrackerColors
import com.stocktracker.core.designsystem.components.AppCard
import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.PortfolioRow

/** Card-per-event dividend list — same overflow bug and same fix as LotListSection. */
@Composable
internal fun DividendSection(
    row: PortfolioRow,
    dividendsByTicker: Map<String, List<DividendEvent>>,
    taxOverrides: Map<String, Double>,
    displayCurrency: String,
    rates: Map<String, Double>,
    onSetDivTax: (String, String, Double) -> Unit,
    onClearDivTax: (String, String) -> Unit,
) {
    fun isRelevant(lot: com.stocktracker.core.model.Position, date: String): Boolean {
        val sellDate = lot.sellDate
        return lot.buyDate <= date && (sellDate == null || sellDate > date)
    }

    val tickerDivs = dividendsByTicker[row.ticker.uppercase()] ?: emptyList()
    val relevantDivs = tickerDivs.filter { div -> row.positions.any { lot -> isRelevant(lot, div.date) } }
    if (relevantDivs.isEmpty()) return

    var editTarget by remember { mutableStateOf<DividendEvent?>(null) }

    Column(Modifier.fillMaxWidth().padding(top = Spacing.md)) {
        Text("Dividends received", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            relevantDivs.forEach { div ->
                val shares = row.positions.filter { lot -> isRelevant(lot, div.date) }.sumOf { it.quantity }
                val overrideKey = "${row.ticker.uppercase()}::${div.date}"
                val defaultRate = com.stocktracker.core.calc.getDividendTaxRate(row.ticker)
                val appliedRate = taxOverrides[overrideKey] ?: defaultRate
                val isOverridden = overrideKey in taxOverrides
                val gross = shares * div.amount
                val net = gross * (1 - appliedRate)
                val grossDc = com.stocktracker.core.calc.convert(gross, div.currency, displayCurrency, rates)
                val netDc = com.stocktracker.core.calc.convert(net, div.currency, displayCurrency, rates)

                DividendCard(
                    div = div,
                    grossDisplay = grossDc,
                    appliedRatePct = appliedRate * 100,
                    isOverridden = isOverridden,
                    netDisplay = netDc,
                    onEdit = { editTarget = div },
                )
            }
        }
    }

    editTarget?.let { div ->
        val overrideKey = "${row.ticker.uppercase()}::${div.date}"
        DivTaxEditDialog(
            ticker = row.ticker,
            date = div.date,
            currentRate = taxOverrides[overrideKey],
            defaultRate = com.stocktracker.core.calc.getDividendTaxRate(row.ticker),
            onSave = { rate -> onSetDivTax(row.ticker, div.date, rate); editTarget = null },
            onClear = { onClearDivTax(row.ticker, div.date); editTarget = null },
            onDismiss = { editTarget = null },
        )
    }
}

@Composable
internal fun DividendCard(
    div: DividendEvent,
    grossDisplay: Double,
    appliedRatePct: Double,
    isOverridden: Boolean,
    netDisplay: Double,
    onEdit: () -> Unit,
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(div.date, style = NumericTypography.labelMedium, maxLines = 1)
        DividendField("Gross", formatMoney(grossDisplay))
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Tax rate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                formatPercent(appliedRatePct),
                style = NumericTypography.labelMedium,
                color = if (isOverridden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onEdit),
            )
        }
        DividendField("Net", formatMoney(netDisplay), valueColor = StockTrackerColors.gain)
    }
}

@Composable
private fun DividendField(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = NumericTypography.labelMedium, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
