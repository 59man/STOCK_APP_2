package com.stocktracker.feature.portfolio

import com.stocktracker.core.calc.Benchmark
import com.stocktracker.core.calc.ChartRange
import com.stocktracker.core.calc.PortfolioChartPoint
import com.stocktracker.core.model.PositionType

/** Mirrors the `PnlView` union ('return' | 'value') in PortfolioPnLChart.tsx. */
enum class PnlView { RETURN, VALUE }

data class PortfolioChartUiState(
    val range: ChartRange = ChartRange.ALL,
    val view: PnlView = PnlView.RETURN,
    val loading: Boolean = false,
    val error: String? = null,
    val points: List<PortfolioChartPoint> = emptyList(),
    val displayCurrency: String = "CZK",
    /** Types present in the portfolio, in display order — one chip each. */
    val heldTypes: List<PositionType> = emptyList(),
    /** Filter in force (stale stored types already dropped); empty means All. */
    val typeFilter: Set<PositionType> = emptySet(),
    val benchmark: Benchmark? = null,
    /** Benchmark return per point, same length as [points]; null while loading or when off. */
    val benchmarkValues: List<Double>? = null,
)

sealed interface PortfolioChartAction {
    data class SetRange(val range: ChartRange) : PortfolioChartAction
    data class SetView(val view: PnlView) : PortfolioChartAction
    data class ToggleType(val type: PositionType) : PortfolioChartAction
    data object ClearTypes : PortfolioChartAction
    data class SetBenchmark(val benchmark: Benchmark?) : PortfolioChartAction
}
