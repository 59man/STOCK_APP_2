package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stocktracker.core.designsystem.StockTrackerColors
import com.stocktracker.core.designsystem.chart.AreaLineChart
import com.stocktracker.core.designsystem.chart.ChartSeries
import com.stocktracker.core.designsystem.chart.MultiLineChart
import java.util.Locale

/**
 * Portfolio-level total-return / value chart card — mirrors PortfolioPnLChart.tsx's
 * header (title + range + Total-Return/Portfolio-Value toggle) and its two chart
 * views. [portfolioId] drives which portfolio the underlying [PortfolioChartViewModel]
 * observes; the ViewModel instance itself is shared across portfolio switches
 * (`hiltViewModel()` with no key — same pattern as [PortfolioListViewModel]'s own
 * `activePortfolioId`, not a per-portfolio remount).
 */
@Composable
fun PortfolioPnlChartCard(
    portfolioId: String?,
    modifier: Modifier = Modifier,
    viewModel: PortfolioChartViewModel = hiltViewModel(),
) {
    LaunchedEffect(portfolioId) { viewModel.setPortfolioId(portfolioId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            if (uiState.view == PnlView.RETURN) "Portfolio Total Return" else "Portfolio Value",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (uiState.view == PnlView.RETURN) "price P&L + net dividends (after withholding tax)"
            else "capital in open positions vs. mark-to-market value",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ViewToggleButton("Total Return", uiState.view == PnlView.RETURN) {
                viewModel.onAction(PortfolioChartAction.SetView(PnlView.RETURN))
            }
            ViewToggleButton("Portfolio Value", uiState.view == PnlView.VALUE) {
                viewModel.onAction(PortfolioChartAction.SetView(PnlView.VALUE))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
            RangeTabs(selected = uiState.range) { viewModel.onAction(PortfolioChartAction.SetRange(it)) }
        }

        when {
            uiState.loading -> ChartPlaceholder("Loading portfolio history…")
            uiState.error != null -> ChartPlaceholder("History error: ${uiState.error}")
            uiState.points.isEmpty() -> ChartPlaceholder("No data for this range.")
            uiState.view == PnlView.RETURN -> {
                val finalPnl = uiState.points.last().pnl
                val color = if (finalPnl >= 0) StockTrackerColors.Gain else StockTrackerColors.Loss
                Text(
                    fmtCurrencyChart(finalPnl, uiState.displayCurrency),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                AreaLineChart(
                    values = uiState.points.map { it.pnl },
                    labels = uiState.points.map { formatAxisDate(it.date, uiState.range) },
                    color = color,
                    showZeroLine = true,
                )
            }
            else -> {
                MultiLineChart(
                    series = listOf(
                        ChartSeries("Cost Basis", uiState.points.map { it.costBasis }, Color(0xFF64748B)),
                        ChartSeries("Current Value", uiState.points.map { it.currentValue }, Color(0xFF3B82F6)),
                    ),
                    labels = uiState.points.map { formatAxisDate(it.date, uiState.range) },
                )
            }
        }
    }
}

@Composable
private fun ViewToggleButton(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

private fun fmtCurrencyChart(v: Double, currency: String): String = String.format(Locale.US, "%,.0f %s", v, currency)
