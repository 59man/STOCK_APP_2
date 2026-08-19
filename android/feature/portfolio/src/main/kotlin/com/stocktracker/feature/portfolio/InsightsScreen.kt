package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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

            if (uiState.visibleRows.isEmpty()) {
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
                    item(key = "pnl-chart") { PortfolioPnlChartCard(portfolioId = uiState.activePortfolioId) }
                    item(key = "pie-charts") {
                        PortfolioPieChartsCard(uiState.visibleRows, uiState.displayCurrency, uiState.rates)
                    }
                }
            }
        }
    }
}
