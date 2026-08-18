package com.stocktracker.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.core.calc.ChartRange
import com.stocktracker.core.calc.TickerChartHistory
import com.stocktracker.core.calc.buildEffectiveHistories
import com.stocktracker.core.calc.buildPortfolioChartData
import com.stocktracker.core.calc.convert
import com.stocktracker.core.data.DivTaxOverrideRepository
import com.stocktracker.core.data.DividendRepository
import com.stocktracker.core.data.FxRateRepository
import com.stocktracker.core.data.ManualPriceRepository
import com.stocktracker.core.data.PositionRepository
import com.stocktracker.core.data.QuoteRepository
import com.stocktracker.core.data.SettingsRepository
import com.stocktracker.core.model.NO_FEED_TICKERS
import com.stocktracker.core.model.PriceHistory
import com.stocktracker.core.model.Position
import com.stocktracker.core.network.HistoryClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Portfolio-level total-return / value chart — mirrors PortfolioPnLChart.tsx.
 * Network history fetch (via [HistoryClient], direct to Yahoo) is triggered
 * only when the active portfolio's ticker set or the selected range changes;
 * live quotes, dividends, and FX rates are read from the same singleton
 * repositories [PortfolioListViewModel] already keeps populated, so this
 * ViewModel never re-fetches them itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortfolioChartViewModel @Inject constructor(
    private val positionRepository: PositionRepository,
    private val manualPriceRepository: ManualPriceRepository,
    private val divTaxOverrideRepository: DivTaxOverrideRepository,
    private val dividendRepository: DividendRepository,
    private val quoteRepository: QuoteRepository,
    private val fxRateRepository: FxRateRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val portfolioId = MutableStateFlow<String?>(null)
    private val range = MutableStateFlow(ChartRange.ALL)
    private val view = MutableStateFlow(PnlView.RETURN)

    fun setPortfolioId(id: String?) {
        portfolioId.value = id
    }

    private val positions = portfolioId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else positionRepository.observe(id)
    }
    private val manualPrices = portfolioId.flatMapLatest { id ->
        if (id == null) flowOf(emptyMap()) else manualPriceRepository.observe(id)
    }
    private val taxOverrides = portfolioId.flatMapLatest { id ->
        if (id == null) flowOf(emptyMap()) else divTaxOverrideRepository.observe(id)
    }

    private data class HistoryFetchState(
        val histories: Map<String, TickerChartHistory> = emptyMap(),
        val fxHistories: Map<String, PriceHistory> = emptyMap(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val historyState = MutableStateFlow(HistoryFetchState())

    init {
        // Re-fetch only when the ticker *set* or the range actually changes — not on
        // every lot edit — mirroring the `[tickers.join(','), yahooRange]` dependency
        // list on PortfolioPnLChart.tsx's fetch effect.
        viewModelScope.launch {
            combine(positions, range) { pos, r -> Triple(pos, pos.map { it.ticker.uppercase() }.distinct().sorted(), r) }
                .distinctUntilChanged { a, b -> a.second == b.second && a.third == b.third }
                .collectLatest { (pos, _, r) -> fetchHistories(pos, r) }
        }
    }

    private suspend fun fetchHistories(positions: List<Position>, range: ChartRange) = coroutineScope {
        val tickers = positions.map { it.ticker }.distinct()
        if (tickers.isEmpty()) {
            historyState.value = HistoryFetchState()
            return@coroutineScope
        }
        historyState.update { it.copy(loading = true, error = null) }
        try {
            val fetched = tickers.map { ticker ->
                async {
                    if (NO_FEED_TICKERS.contains(ticker.uppercase())) {
                        ticker to null
                    } else {
                        ticker to runCatching { HistoryClient.fetchHistory(ticker, range.yahooParam) }.getOrNull()
                    }
                }
            }.awaitAll()

            val histories = fetched.mapNotNull { (ticker, result) ->
                if (result == null || result.points.isEmpty()) return@mapNotNull null
                val fallbackCurrency = positions.firstOrNull { it.ticker.uppercase() == ticker.uppercase() }?.currency ?: "CZK"
                ticker.uppercase() to TickerChartHistory(result.points, result.currency ?: fallbackCurrency)
            }.toMap()

            val neededFxCurrencies = mutableSetOf("USD", "EUR")
            positions.forEach { neededFxCurrencies.add(it.currency) }
            histories.values.forEach { neededFxCurrencies.add(it.currency) }
            neededFxCurrencies.remove("CZK")
            val fxHistories = neededFxCurrencies
                .map { currency -> currency to async { HistoryClient.fetchFxHistory(currency) } }
                .associate { (currency, deferred) -> currency to deferred.await() }

            historyState.value = HistoryFetchState(histories = histories, fxHistories = fxHistories, loading = false)
        } catch (e: Exception) {
            historyState.update { it.copy(loading = false, error = e.message ?: "History fetch failed") }
        }
    }

    private data class Inputs1(
        val positions: List<Position>,
        val manualPrices: Map<String, com.stocktracker.core.model.ManualPriceEntry>,
        val taxOverrides: Map<String, Double>,
    )

    private data class Inputs2(
        val quotes: Map<String, com.stocktracker.core.model.Quote>,
        val dividends: Map<String, List<com.stocktracker.core.model.DividendEvent>>,
        val rates: Map<String, Double>,
    )

    private data class Prefs(val range: ChartRange, val view: PnlView, val displayCurrency: String)

    private val inputs1 = combine(positions, manualPrices, taxOverrides) { p, m, t -> Inputs1(p, m, t) }
    private val inputs2 = combine(quoteRepository.quotes, dividendRepository.dividends, fxRateRepository.rates) { q, d, r -> Inputs2(q, d, r) }
    private val prefs = combine(range, view, settingsRepository.settings) { r, v, s -> Prefs(r, v, s.displayCurrency) }

    val uiState: StateFlow<PortfolioChartUiState> = combine(inputs1, inputs2, historyState, prefs) { in1, in2, hist, p ->
        when {
            in1.positions.isEmpty() -> PortfolioChartUiState(range = p.range, view = p.view, displayCurrency = p.displayCurrency)
            hist.loading -> PortfolioChartUiState(range = p.range, view = p.view, loading = true, displayCurrency = p.displayCurrency)
            hist.error != null -> PortfolioChartUiState(range = p.range, view = p.view, error = hist.error, displayCurrency = p.displayCurrency)
            else -> {
                val tickers = in1.positions.map { it.ticker }.distinct()
                val effectiveHistories = buildEffectiveHistories(
                    histories = hist.histories,
                    manualPrices = in1.manualPrices,
                    quotes = in2.quotes,
                    positions = in1.positions,
                    tickers = tickers,
                )
                val points = buildPortfolioChartData(
                    positions = in1.positions,
                    dividendsByTicker = in2.dividends,
                    effectiveHistories = effectiveHistories,
                    fxHistories = hist.fxHistories,
                    range = p.range,
                    displayCurrency = p.displayCurrency,
                    taxOverrides = in1.taxOverrides,
                    spotConvert = { amount, from, to -> convert(amount, from, to, in2.rates) },
                )
                PortfolioChartUiState(range = p.range, view = p.view, loading = false, points = points, displayCurrency = p.displayCurrency)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioChartUiState())

    fun onAction(action: PortfolioChartAction) {
        when (action) {
            is PortfolioChartAction.SetRange -> range.value = action.range
            is PortfolioChartAction.SetView -> view.value = action.view
        }
    }
}
