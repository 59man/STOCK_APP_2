package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The PnL chart + pie charts, pulled out of the Portfolio tab's single mega-scroll screen into
 * their own destination. Shares [PortfolioListViewModel] with [PortfolioListRoute] (both are
 * scoped to the same nested nav graph in MainActivity) so the active-portfolio selection stays
 * in sync between the two tabs instead of each resolving its own default.
 */
@Composable
fun InsightsRoute(viewModel: PortfolioListViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    InsightsScreen(uiState = uiState, onAction = viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InsightsScreen(uiState: PortfolioListUiState, onAction: (PortfolioListAction) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Insights") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PortfolioTabs(uiState, onAction)
            CurrencyTabs(uiState, onAction)

            if (uiState.isLoading || uiState.isSwitchingPortfolio) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            // All rows, closed included — matches the web summary + pie charts (PortfolioTable /
            // PortfolioContent pass the full row list). visibleRows is the Portfolio tab's
            // "Show closed" list filter; Insights has no such toggle, so using it here silently
            // dropped fully-sold tickers' realized P&L and dividends from every total.
            } else if (uiState.rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Add a position to see charts",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "summary") {
                        SummaryHeader(uiState.rows, uiState.displayCurrency, uiState.rates, uiState.portfolioIrr)
                    }
                    item(key = "pnl-chart") { PortfolioPnlChartCard(portfolioId = uiState.activePortfolioId) }
                    item(key = "pie-charts") {
                        PortfolioPieChartsCard(uiState.rows, uiState.displayCurrency, uiState.rates)
                    }
                    item(key = "dividend-forecast") {
                        DividendForecastCard(
                            positions = uiState.rows.flatMap { it.positions },
                            dividends = uiState.dividendsByTicker,
                            displayCurrency = uiState.displayCurrency,
                            rates = uiState.rates,
                        )
                    }
                    item(key = "tax") {
                        TaxCard(positions = uiState.rows.flatMap { it.positions }, rates = uiState.rates)
                    }
                }
            }
        }
    }
}
