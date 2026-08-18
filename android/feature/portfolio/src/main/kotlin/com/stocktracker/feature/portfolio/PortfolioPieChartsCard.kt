package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.core.calc.convert
import com.stocktracker.core.designsystem.chart.DonutChart
import com.stocktracker.core.designsystem.chart.DonutSlice
import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.PositionType
import java.util.Locale

private enum class GroupBy { TYPE, TICKER, CURRENCY }

private val TypeColors = mapOf(
    PositionType.STOCK to Color(0xFF4F8EF7),
    PositionType.ETF to Color(0xFF50C878),
    PositionType.FUND to Color(0xFFC97FF5),
    PositionType.COMMODITY to Color(0xFFF5C842),
)

private val TypeLabels = mapOf(
    PositionType.STOCK to "Stocks",
    PositionType.ETF to "ETFs",
    PositionType.FUND to "Funds",
    PositionType.COMMODITY to "Commodities",
)

private val CurrencyColors = mapOf(
    "CZK" to Color(0xFF4F8EF7), "USD" to Color(0xFF50C878), "EUR" to Color(0xFFC97FF5),
    "GBP" to Color(0xFFF5C842), "CHF" to Color(0xFFFF9F1C), "JPY" to Color(0xFFE63946),
    "CAD" to Color(0xFF4CC9F0), "AUD" to Color(0xFFF4A261),
)

private val Palette = listOf(
    0xFFE63946, 0xFF2EC4B6, 0xFFFF9F1C, 0xFF4361EE, 0xFF57CC99,
    0xFFF72585, 0xFF4CC9F0, 0xFFF4A261, 0xFF7B2D8B, 0xFFB5E48C,
    0xFFFFD166, 0xFFEF476F, 0xFF06D6A0, 0xFF118AB2, 0xFFFF6B6B,
    0xFF9B5DE5, 0xFF00BBF9, 0xFFFEE440, 0xFFFB5607, 0xFF3A86FF,
    0xFF8338EC, 0xFFFFBE0B, 0xFFF15BB5, 0xFF00F5D4,
).map { Color(it) }

private data class GroupTotals(var costBasis: Double = 0.0, var currentValue: Double = 0.0, var totalReturn: Double = 0.0)

/**
 * Three donut charts — Cost Basis, Current Value, Total Return incl.
 * Dividends — grouped by asset type, ticker, or native trading currency.
 * Pure aggregation of already-computed [rows]; no network fetch of its own,
 * unlike [PriceChartCard] / the portfolio total-return chart. Mirrors
 * PortfolioPieCharts.tsx.
 */
@Composable
fun PortfolioPieChartsCard(rows: List<PortfolioRow>, displayCurrency: String, rates: Map<String, Double>) {
    if (rows.isEmpty()) return
    var groupBy by remember { mutableStateOf(GroupBy.TYPE) }
    fun cv(amount: Double, from: String) = convert(amount, from, displayCurrency, rates)

    val tickerColors = remember(rows) {
        rows.mapIndexed { i, row -> row.ticker to Palette[i % Palette.size] }.toMap()
    }

    data class Charts(val costBasis: List<DonutSlice>, val currentValue: List<DonutSlice>, val totalReturn: List<DonutSlice>)

    val charts = when (groupBy) {
        GroupBy.TYPE -> {
            val agg = linkedMapOf<PositionType, GroupTotals>()
            rows.forEach { row ->
                val t = agg.getOrPut(row.type) { GroupTotals() }
                t.costBasis += cv(row.costBasis, row.currency)
                t.currentValue += cv(row.currentValue, row.currency)
                t.totalReturn += cv(row.totalReturn, row.currency)
            }
            Charts(
                costBasis = agg.map { (type, v) -> DonutSlice(TypeLabels[type] ?: type.name, v.costBasis, TypeColors[type] ?: Color.Gray) },
                currentValue = agg.map { (type, v) -> DonutSlice(TypeLabels[type] ?: type.name, v.currentValue, TypeColors[type] ?: Color.Gray) },
                totalReturn = agg.filterValues { it.totalReturn > 0 }
                    .map { (type, v) -> DonutSlice(TypeLabels[type] ?: type.name, v.totalReturn, TypeColors[type] ?: Color.Gray) },
            )
        }
        GroupBy.CURRENCY -> {
            val agg = linkedMapOf<String, GroupTotals>()
            rows.forEach { row ->
                val t = agg.getOrPut(row.nativeCurrency) { GroupTotals() }
                t.costBasis += cv(row.costBasis, row.currency)
                t.currentValue += cv(row.currentValue, row.currency)
                t.totalReturn += cv(row.totalReturn, row.currency)
            }
            val entries = agg.entries.toList()
            fun colorFor(currency: String, i: Int) = CurrencyColors[currency] ?: Palette[i % Palette.size]
            Charts(
                costBasis = entries.mapIndexed { i, e -> DonutSlice(e.key, e.value.costBasis, colorFor(e.key, i)) },
                currentValue = entries.mapIndexed { i, e -> DonutSlice(e.key, e.value.currentValue, colorFor(e.key, i)) },
                totalReturn = entries.mapIndexed { i, e -> DonutSlice(e.key, e.value.totalReturn, colorFor(e.key, i)) }
                    .filter { it.value > 0 },
            )
        }
        GroupBy.TICKER -> Charts(
            costBasis = rows.map { r -> DonutSlice(r.ticker, cv(r.costBasis, r.currency), tickerColors[r.ticker] ?: Color.Gray) },
            currentValue = rows.map { r -> DonutSlice(r.ticker, cv(r.currentValue, r.currency), tickerColors[r.ticker] ?: Color.Gray) },
            totalReturn = rows.map { r -> DonutSlice(r.ticker, cv(r.totalReturn, r.currency), tickerColors[r.ticker] ?: Color.Gray) }
                .filter { it.value > 0 },
        )
    }

    fun fmt(v: Double): String = String.format(Locale.US, "%,.0f %s", v, displayCurrency)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Portfolio Distribution", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            GroupToggleButton("By Type", groupBy == GroupBy.TYPE) { groupBy = GroupBy.TYPE }
            GroupToggleButton("By Ticker", groupBy == GroupBy.TICKER) { groupBy = GroupBy.TICKER }
            GroupToggleButton("By Currency", groupBy == GroupBy.CURRENCY) { groupBy = GroupBy.CURRENCY }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Cost Basis", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                DonutChart(charts.costBasis, valueFormatter = ::fmt)
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Current Value", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                DonutChart(charts.currentValue, valueFormatter = ::fmt, emptyLabel = "No open positions")
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Total Return incl. Dividends", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                DonutChart(charts.totalReturn, valueFormatter = ::fmt, emptyLabel = "No returns yet")
            }
        }
    }
}

@Composable
private fun GroupToggleButton(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
            contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}
