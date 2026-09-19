package com.stocktracker.feature.portfolio

import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.Portfolio
import com.stocktracker.core.calc.sortedForDisplay
import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.SortOrder

data class PortfolioListUiState(
    val portfolios: List<Portfolio> = emptyList(),
    val activePortfolioId: String? = null,
    val rows: List<PortfolioRow> = emptyList(),
    val showClosed: Boolean = false,
    /** App-startup gate only (see PortfolioListViewModel.startupReady) — latched false once the first full screen is ready. */
    val isLoading: Boolean = true,
    /** Active portfolio just changed and Room hasn't emitted its lots yet — rows still belong to the previous one. */
    val isSwitchingPortfolio: Boolean = false,
    val lastSyncedAt: String? = null,
    val conflictCount: Int = 0,
    val displayCurrency: String = "CZK",
    val rates: Map<String, Double> = com.stocktracker.core.calc.DEFAULT_RATES,
    val dividendsByTicker: Map<String, List<DividendEvent>> = emptyMap(),
    val divTaxOverrides: Map<String, Double> = emptyMap(),
    val portfolioIrr: Double? = null,
    val sortOrder: SortOrder = SortOrder(),
) {
    /**
     * Sorted after the closed filter, so "Show closed" and the sort compose rather than fight:
     * hiding closed rows never reorders the ones that stay.
     */
    val visibleRows: List<PortfolioRow>
        get() = (if (showClosed) rows else rows.filterNot { it.isClosed })
            .sortedForDisplay(sortOrder, displayCurrency, rates)
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
    data class SetDisplayCurrency(val currency: String) : PortfolioListAction
    /** Selects [field]; passing the field that is already active flips the direction. */
    data class SetSort(val field: com.stocktracker.core.model.SortField) : PortfolioListAction
}
