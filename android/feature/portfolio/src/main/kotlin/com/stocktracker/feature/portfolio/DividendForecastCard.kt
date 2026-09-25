package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.stocktracker.core.calc.ForecastEntry
import com.stocktracker.core.calc.convert
import com.stocktracker.core.calc.dividendForecast
import com.stocktracker.core.designsystem.NumericTypography
import com.stocktracker.core.designsystem.Spacing
import com.stocktracker.core.designsystem.StockTrackerColors
import com.stocktracker.core.designsystem.components.AppCard
import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.Position
import java.time.LocalDate

/** Estimated dividends for the next 12 months on the Insights tab. Mirrors DividendCalendar.tsx. */
@Composable
internal fun DividendForecastCard(
    positions: List<Position>,
    dividends: Map<String, List<DividendEvent>>,
    displayCurrency: String,
    rates: Map<String, Double>,
) {
    val entries = dividendForecast(positions, dividends, LocalDate.now().toString())
    if (entries.isEmpty()) return
    DividendForecastContent(entries, displayCurrency) { amount, from -> convert(amount, from, displayCurrency, rates) }
}

@Composable
internal fun DividendForecastContent(entries: List<ForecastEntry>, displayCurrency: String, toDisplay: (Double, String) -> Double) {
    val total = entries.sumOf { toDisplay(it.net, it.currency) }
    AppCard(Modifier.fillMaxWidth()) {
        Text("Dividends — next 12 months", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "+${formatMoney(total)} $displayCurrency",
            style = NumericTypography.titleMedium,
            color = StockTrackerColors.gain,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        entries.take(6).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${formatDisplayDate(e.date)} · ${e.ticker}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Wraps rather than clips when a large amount squeezes it at big font scales.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = Spacing.md),
                )
                Text(formatMoney(toDisplay(e.net, e.currency)), style = NumericTypography.labelMedium, maxLines = 1)
            }
        }
        Text(
            "Estimated: last 12 months of payments repeated on today's shares, after default withholding tax.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md),
        )
    }
}
