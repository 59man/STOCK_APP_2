package com.stocktracker.feature.portfolio

import com.stocktracker.core.calc.ChartRange
import com.stocktracker.core.calc.PortfolioChartPoint

/** Mirrors the `PnlView` union ('return' | 'value') in PortfolioPnLChart.tsx. */
enum class PnlView { RETURN, VALUE }

data class PortfolioChartUiState(
    val range: ChartRange = ChartRange.ALL,
    val view: PnlView = PnlView.RETURN,
    val loading: Boolean = false,
    val error: String? = null,
    val points: List<PortfolioChartPoint> = emptyList(),
    val displayCurrency: String = "CZK",
)

sealed interface PortfolioChartAction {
    data class SetRange(val range: ChartRange) : PortfolioChartAction
    data class SetView(val view: PnlView) : PortfolioChartAction
}
