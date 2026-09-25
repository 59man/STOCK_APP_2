package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.stocktracker.core.calc.LotTaxStatus
import com.stocktracker.core.calc.TaxKind
import com.stocktracker.core.calc.YearSummary
import com.stocktracker.core.calc.convert
import com.stocktracker.core.calc.isOpenLot
import com.stocktracker.core.calc.lotTaxStatus
import com.stocktracker.core.calc.priceAt
import com.stocktracker.core.calc.realizedByYear
import com.stocktracker.core.designsystem.NumericTypography
import com.stocktracker.core.designsystem.Spacing
import com.stocktracker.core.designsystem.StockTrackerColors
import com.stocktracker.core.designsystem.components.AppCard
import com.stocktracker.core.designsystem.components.Badge
import com.stocktracker.core.model.Position
import com.stocktracker.core.network.HistoryClient
import java.time.LocalDate

/**
 * Czech 3-year time test on the Insights tab: realized gains per sell year split into taxable
 * and exempt, in CZK at each trade's own date, plus the open lots that pass the test next.
 * Mirrors src/components/TaxPanel.tsx.
 */
@Composable
internal fun TaxCard(positions: List<Position>, rates: Map<String, Double>) {
    val currencies = positions.map { it.currency }.filter { it != "CZK" }.distinct().sorted()
    // FX histories are cached per session inside HistoryClient, so this is one fetch per currency.
    val years by produceState(initialValue = emptyList<YearSummary>(), positions, rates) {
        val fx = currencies.associateWith { HistoryClient.fetchFxHistory(it) }
        value = realizedByYear(positions) { amount, cur, date ->
            if (cur == "CZK") amount
            else fx[cur]?.let { priceAt(it, date) }?.let { amount * it } ?: convert(amount, cur, "CZK", rates)
        }
    }
    val today = LocalDate.now().toString()
    val upcoming = positions.filter { isOpenLot(it) && it.quantity > 0 }
        .map { it to lotTaxStatus(it, today) }
        .filter { (_, s) -> s.kind == TaxKind.PENDING }
        .sortedBy { (_, s) -> s.freeFrom }
        .take(6)
    if (years.isEmpty() && upcoming.isEmpty()) return
    TaxCardContent(years, upcoming)
}

@Composable
internal fun TaxCardContent(years: List<YearSummary>, upcoming: List<Pair<Position, LotTaxStatus>>) {
    AppCard(Modifier.fillMaxWidth()) {
        Text("Czech tax — 3-year time test", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        years.forEach { y ->
            Column(Modifier.fillMaxWidth().padding(top = Spacing.md)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(y.year.toString(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (y.proceedsTaxable > 0 && y.underSmallProceedsLimit) {
                        Badge("≤ 100k proceeds", containerColor = StockTrackerColors.gain.copy(alpha = 0.16f), contentColor = StockTrackerColors.gain)
                    }
                }
                TaxLine("Taxable gain", signedMoney(y.gainTaxable) + " CZK")
                TaxLine("Exempt gain", signedMoney(y.gainExempt) + " CZK")
                TaxLine("Taxable proceeds", formatMoney(y.proceedsTaxable) + " CZK")
            }
        }
        if (upcoming.isNotEmpty()) {
            Text(
                "Next lots to pass the test",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.md),
            )
            upcoming.forEach { (lot, s) -> TaxLine("${lot.ticker} · ${formatQty(lot.quantity)}", formatDisplayDate(s.freeFrom)) }
        }
        Text(
            "Estimate — not tax advice. Gains in CZK at buy- and sell-date rates. Dividends not included.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md),
        )
    }
}

@Composable
private fun TaxLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(end = Spacing.md),
        )
        Text(value, style = NumericTypography.labelMedium, maxLines = 1)
    }
}

/** One-line tax status for a lot card: "Tax-free", "Taxable", or when it becomes tax-free. */
internal fun taxStatusText(s: LotTaxStatus): String = when (s.kind) {
    TaxKind.EXEMPT -> "Tax-free"
    TaxKind.TAXABLE -> "Taxable"
    TaxKind.PENDING -> if (s.daysLeft <= 60) "Tax-free in ${s.daysLeft} d" else "Tax-free from ${formatDisplayDate(s.freeFrom)}"
}
