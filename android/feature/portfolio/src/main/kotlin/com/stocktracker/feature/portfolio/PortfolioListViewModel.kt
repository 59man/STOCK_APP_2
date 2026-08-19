package com.stocktracker.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.core.calc.computePortfolioIrr
import com.stocktracker.core.calc.convert
import com.stocktracker.core.calc.deriveRow
import com.stocktracker.core.data.DivTaxOverrideRepository
import com.stocktracker.core.data.DividendRepository
import com.stocktracker.core.data.FxRateRepository
import com.stocktracker.core.data.ImportRepository
import com.stocktracker.core.data.ManualPriceRepository
import com.stocktracker.core.data.PortfolioRepository
import com.stocktracker.core.data.PositionRepository
import com.stocktracker.core.data.QuoteRepository
import com.stocktracker.core.data.SettingsRepository
import com.stocktracker.core.data.sync.ConflictCenter
import com.stocktracker.core.data.sync.PendingConflict
import com.stocktracker.core.data.sync.SyncCoordinator
import com.stocktracker.core.data.sync.SyncTarget
import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.Quote
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** [SyncCoordinator.enqueuePush] only needs a string to build a unique work name for the PORTFOLIOS key — it isn't a real portfolio id. */
private const val GLOBAL_SYNC_SCOPE = "portfolios"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortfolioListViewModel @Inject constructor(
    private val portfolioRepository: PortfolioRepository,
    private val positionRepository: PositionRepository,
    private val manualPriceRepository: ManualPriceRepository,
    private val divTaxOverrideRepository: DivTaxOverrideRepository,
    private val settingsRepository: SettingsRepository,
    private val quoteRepository: QuoteRepository,
    private val dividendRepository: DividendRepository,
    private val fxRateRepository: FxRateRepository,
    private val importRepository: ImportRepository,
    private val conflictCenter: ConflictCenter,
    private val syncCoordinator: SyncCoordinator,
) : ViewModel() {

    private val activePortfolioId = MutableStateFlow<String?>(null)
    private val showClosed = MutableStateFlow(false)

    private data class PortfolioData(
        val positions: List<Position>,
        val manualPrices: Map<String, ManualPriceEntry>,
        val divTaxOverrides: Map<String, Double>,
    )

    private data class QuoteState(
        val quotes: Map<String, Quote>,
        val loading: Set<String>,
        val errors: Map<String, String>,
    )

    private data class CombinedState(
        val data: PortfolioData,
        val quotes: QuoteState,
        val divs: Map<String, List<DividendEvent>>,
        val conflicts: List<PendingConflict>,
        val rates: Map<String, Double>,
    )

    private val portfolioData = activePortfolioId.flatMapLatest { id ->
        if (id == null) {
            flowOf(PortfolioData(emptyList(), emptyMap(), emptyMap()))
        } else {
            combine(
                positionRepository.observe(id),
                manualPriceRepository.observe(id),
                divTaxOverrideRepository.observe(id),
            ) { positions, manualPrices, overrides -> PortfolioData(positions, manualPrices, overrides) }
        }
    }

    private val quoteState = combine(quoteRepository.quotes, quoteRepository.loading, quoteRepository.errors) { q, l, e ->
        QuoteState(q, l, e)
    }

    init {
        // Fetch live quotes for every ticker with at least one open lot whenever the position set changes.
        viewModelScope.launch {
            portfolioData.collectLatest { data ->
                val openTickers = data.positions
                    .groupBy { it.ticker }
                    .filterValues { lots -> lots.any { it.sellPrice == null || it.sellPrice == 0.0 || it.sellDate.isNullOrEmpty() } }
                    .keys
                if (openTickers.isNotEmpty()) quoteRepository.fetchTickers(openTickers.toList())
            }
        }
        // Dividend income counts for closed lots too (received while the lot was still open), so
        // fetch the full ticker set rather than just open ones — unlike the live-quote fetch above.
        viewModelScope.launch {
            portfolioData.collectLatest { data ->
                val allTickers = data.positions.map { it.ticker }.distinct()
                if (allTickers.isNotEmpty()) dividendRepository.fetchTickers(allTickers)
            }
        }
        viewModelScope.launch { fxRateRepository.refresh() }
    }

    val uiState: StateFlow<PortfolioListUiState> = combine(
        portfolioRepository.observe(),
        activePortfolioId,
        combine(
            portfolioData, quoteState, dividendRepository.dividends, conflictCenter.pending, fxRateRepository.rates,
        ) { data, quotes, divs, conflicts, rates ->
            CombinedState(data, quotes, divs, conflicts, rates)
        },
        showClosed,
        settingsRepository.settings,
    ) { portfolios, activeId, combined, closedVisible, settings ->
        val (data, quotes, divs, conflicts, rates) = combined
        val resolvedActiveId = activeId ?: portfolios.firstOrNull()?.id
        if (activeId == null && resolvedActiveId != null) activePortfolioId.value = resolvedActiveId

        val today = LocalDate.now().toString()
        val rows = data.positions
            .groupBy { it.ticker }
            .map { (ticker, lots) ->
                val key = ticker.uppercase()
                deriveRow(
                    lots = lots,
                    quoteRaw = quotes.quotes[key],
                    loadingRaw = quotes.loading.contains(key),
                    errorRaw = quotes.errors[key],
                    manualRaw = data.manualPrices[key],
                    dividends = divs[key] ?: emptyList<DividendEvent>(),
                    taxOverrides = data.divTaxOverrides,
                    today = today,
                    convert = { amount, from, to -> convert(amount, from, to, rates) },
                )
            }
            .sortedBy { it.ticker }

        val portfolioIrr = computePortfolioIrr(
            positions = data.positions,
            rows = rows,
            dividendsByTicker = divs,
            taxOverrides = data.divTaxOverrides,
            displayCurrency = settings.displayCurrency,
            today = today,
            convert = { amount, from, to -> convert(amount, from, to, rates) },
        )

        PortfolioListUiState(
            portfolios = portfolios,
            activePortfolioId = resolvedActiveId,
            rows = rows,
            showClosed = closedVisible,
            isLoading = false,
            lastSyncedAt = settings.lastSyncedAt,
            conflictCount = conflicts.size,
            displayCurrency = settings.displayCurrency,
            rates = rates,
            dividendsByTicker = divs,
            divTaxOverrides = data.divTaxOverrides,
            portfolioIrr = portfolioIrr,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioListUiState())

    fun onAction(action: PortfolioListAction) {
        when (action) {
            is PortfolioListAction.SwitchPortfolio -> activePortfolioId.value = action.portfolioId
            is PortfolioListAction.AddPortfolio -> viewModelScope.launch {
                portfolioRepository.add(action.name)
                syncCoordinator.enqueuePush(GLOBAL_SYNC_SCOPE, SyncTarget.PORTFOLIOS)
            }
            is PortfolioListAction.RenamePortfolio -> viewModelScope.launch {
                portfolioRepository.rename(action.portfolioId, action.name)
                syncCoordinator.enqueuePush(GLOBAL_SYNC_SCOPE, SyncTarget.PORTFOLIOS)
            }
            is PortfolioListAction.DeletePortfolio -> viewModelScope.launch {
                // Blocked below one remaining portfolio, mirroring usePortfolios.removePortfolio's guard.
                if (uiState.value.portfolios.size <= 1) return@launch
                portfolioRepository.delete(action.portfolioId)
                syncCoordinator.enqueuePush(GLOBAL_SYNC_SCOPE, SyncTarget.PORTFOLIOS)
            }
            PortfolioListAction.ToggleShowClosed -> showClosed.update { !it }
            PortfolioListAction.Refresh -> refresh()
            is PortfolioListAction.DeletePosition -> viewModelScope.launch {
                val portfolioId = activePortfolioId.value ?: return@launch
                positionRepository.delete(portfolioId, action.positionId)
                syncCoordinator.enqueuePush(portfolioId, SyncTarget.POSITIONS)
            }
            is PortfolioListAction.SellPositions -> viewModelScope.launch {
                val portfolioId = activePortfolioId.value ?: return@launch
                positionRepository.sell(portfolioId, action.positionIds, action.sellPrice, action.sellDate)
                syncCoordinator.enqueuePush(portfolioId, SyncTarget.POSITIONS)
            }
            is PortfolioListAction.UpdatePosition -> viewModelScope.launch {
                val portfolioId = activePortfolioId.value ?: return@launch
                positionRepository.upsert(portfolioId, action.position)
                syncCoordinator.enqueuePush(portfolioId, SyncTarget.POSITIONS)
            }
            is PortfolioListAction.SetDivTax -> viewModelScope.launch {
                val portfolioId = activePortfolioId.value ?: return@launch
                divTaxOverrideRepository.set(portfolioId, action.ticker, action.date, action.rate)
                syncCoordinator.enqueuePush(portfolioId, SyncTarget.DIV_TAX_OVERRIDES)
            }
            is PortfolioListAction.ClearDivTax -> viewModelScope.launch {
                val portfolioId = activePortfolioId.value ?: return@launch
                divTaxOverrideRepository.clear(portfolioId, action.ticker, action.date)
                syncCoordinator.enqueuePush(portfolioId, SyncTarget.DIV_TAX_OVERRIDES)
            }
        }
    }

    /** Null when no portfolio is active — the caller (Route) skips the export flow in that case. */
    suspend fun exportActivePortfolio(): String? {
        val portfolioId = activePortfolioId.value ?: return null
        return importRepository.exportPortfolio(portfolioId)
    }

    fun onEnterForeground() = refresh()

    private fun refresh() {
        viewModelScope.launch {
            syncCoordinator.pullPortfolioList()
            // activePortfolioId can still be null here on a fresh Room DB — it's only ever set
            // as a side effect inside the uiState combine block below, which needs an active
            // collector and a portfolios emission to have already run. Resolve the same
            // first-portfolio fallback directly against the just-pulled list instead of trusting
            // that side effect has fired yet, otherwise positions silently never sync after a
            // fresh install until the app happens to background/foreground once more.
            val id = activePortfolioId.value ?: portfolioRepository.observe().first().firstOrNull()?.id
            id?.let { syncCoordinator.pullPortfolio(it) }
        }
    }
}
