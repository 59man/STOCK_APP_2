package com.stocktracker.feature.portfolio

import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.Portfolio
import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.Position

data class PortfolioListUiState(
    val portfolios: List<Portfolio> = emptyList(),
    val activePortfolioId: String? = null,
    val rows: List<PortfolioRow> = emptyList(),
    val showClosed: Boolean = false,
    val isLoading: Boolean = true,
    val lastSyncedAt: String? = null,
    val conflictCount: Int = 0,
    val displayCurrency: String = "CZK",
    val rates: Map<String, Double> = com.stocktracker.core.calc.DEFAULT_RATES,
    val dividendsByTicker: Map<String, List<DividendEvent>> = emptyMap(),
    val divTaxOverrides: Map<String, Double> = emptyMap(),
    val portfolioIrr: Double? = null,
) {
    val visibleRows: List<PortfolioRow> get() = if (showClosed) rows else rows.filterNot { it.isClosed }
    val closedCount: Int get() = rows.count { it.isClosed }
}

sealed interface PortfolioListAction {
    data class SwitchPortfolio(val portfolioId: String) : PortfolioListAction
    data class AddPortfolio(val name: String) : PortfolioListAction
    data class RenamePortfolio(val portfolioId: String, val name: String) : PortfolioListAction
    data class DeletePortfolio(val portfolioId: String) : PortfolioListAction
    data object ToggleShowClosed : PortfolioListAction
    data object Refresh : PortfolioListAction
    data class DeletePosition(val positionId: String) : PortfolioListAction
    data class SellPositions(val positionIds: List<String>, val sellPrice: Double, val sellDate: String) : PortfolioListAction
    data class UpdatePosition(val position: Position) : PortfolioListAction
    data class SetDivTax(val ticker: String, val date: String, val rate: Double) : PortfolioListAction
    data class ClearDivTax(val ticker: String, val date: String) : PortfolioListAction
}
