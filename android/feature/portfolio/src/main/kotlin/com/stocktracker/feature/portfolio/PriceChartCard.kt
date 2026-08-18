package com.stocktracker.feature.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.core.calc.ChartRange
import com.stocktracker.core.calc.convert
import com.stocktracker.core.designsystem.StockTrackerColors
import com.stocktracker.core.designsystem.chart.AreaLineChart
import com.stocktracker.core.model.FX_CONVERTED_SET
import com.stocktracker.core.network.HistoryClient

private sealed interface PriceChartState {
    data object Loading : PriceChartState
    data class Loaded(val labels: List<String>, val values: List<Double>) : PriceChartState
    data class Error(val message: String) : PriceChartState
    data object Empty : PriceChartState
}

/**
 * Per-ticker price history — mirrors PriceChart.tsx. Fetches directly from
 * Yahoo via [HistoryClient], bypassing the repository layer entirely, the
 * same way EditTickerDialog's "▶ Test" button already calls QuoteClient
 * straight from a composable (Mobile Sync Blueprint, Phase 2 §00 — quotes
 * and history never route through the user's own server).
 */
@Composable
fun PriceChartCard(
    ticker: String,
    tickerCurrency: String,
    displayCurrency: String,
    rates: Map<String, Double>,
    modifier: Modifier = Modifier,
) {
    var range by remember { mutableStateOf(ChartRange.ONE_YEAR) }
    var state by remember { mutableStateOf<PriceChartState>(PriceChartState.Loading) }

    LaunchedEffect(ticker, range, displayCurrency) {
        state = PriceChartState.Loading
        state = try {
            val result = HistoryClient.fetchHistory(ticker, range.yahooParam)
            val fallbackCurrency = if (FX_CONVERTED_SET.contains(ticker.uppercase())) "CZK" else tickerCurrency
            val factor = convert(1.0, result.currency ?: fallbackCurrency, displayCurrency, rates)
            if (result.points.isEmpty()) {
                PriceChartState.Empty
            } else {
                PriceChartState.Loaded(
                    labels = result.points.map { formatAxisDate(it.first, range) },
                    values = result.points.map { it.second * factor },
                )
            }
        } catch (e: Exception) {
            PriceChartState.Error(e.message ?: "Failed")
        }
    }

    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$ticker chart", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            RangeTabs(selected = range, onSelect = { range = it })
        }
        when (val s = state) {
            PriceChartState.Loading -> ChartPlaceholder("Loading chart…")
            is PriceChartState.Error -> ChartPlaceholder("Chart unavailable: ${s.message}")
            PriceChartState.Empty -> ChartPlaceholder("No data.")
            is PriceChartState.Loaded -> {
                val gain = s.values.size >= 2 && s.values.last() >= s.values.first()
                AreaLineChart(
                    values = s.values,
                    labels = s.labels,
                    color = if (gain) StockTrackerColors.Gain else StockTrackerColors.Loss,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
internal fun RangeTabs(selected: ChartRange, onSelect: (ChartRange) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(ChartRange.entries) { r ->
            val active = r == selected
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                modifier = Modifier.clickable { onSelect(r) },
            ) {
                Text(
                    r.label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ChartPlaceholder(text: String) {
    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
