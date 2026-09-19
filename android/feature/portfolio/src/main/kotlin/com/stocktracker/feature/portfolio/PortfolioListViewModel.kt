package com.stocktracker.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.core.calc.computePortfolioIrr
import com.stocktracker.core.calc.convert
import com.stocktracker.core.calc.deriveRow
import com.stocktracker.core.data.AppSettings
import com.stocktracker.core.data.DivTaxOverrideRepository
import com.stocktracker.core.data.DividendRepository
import com.stocktracker.core.data.FxRateRepository
import com.stocktracker.core.data.ImportRepository
import com.stocktracker.core.data.ManualPriceRepository
import com.stocktracker.core.data.PortfolioRepository
import com.stocktracker.core.data.PositionRepository
import com.stocktracker.core.data.QuoteRepository
import com.stocktracker.core.data.Anchor
import com.stocktracker.core.data.DailyAnchorRepository
import com.stocktracker.core.data.SettingsRepository
import com.stocktracker.core.data.effectiveZoneId
import com.stocktracker.core.data.sync.ConflictCenter
import com.stocktracker.core.data.sync.PendingConflict
import com.stocktracker.core.data.sync.SyncCoordinator
import com.stocktracker.core.data.sync.SyncTarget
import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.SortField
import com.stocktracker.core.model.SortOrder
import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.Quote
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.stocktracker.core.model.NO_FEED_TICKERS
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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

/** Upper bound on the startup loading screen — past this, show whatever has loaded rather than hang on a slow feed. */
private const val STARTUP_TIMEOUT_MS = 10_000L

/** Resume-triggered syncs closer together than this are skipped (every tab switch back to Portfolio fires ON_RESUME). */
private const val RESUME_REFRESH_MIN_INTERVAL_MS = 30_000L

private fun openTickersOf(positions: List<Position>): List<String> =
    positions
        .groupBy { it.ticker }
        .filterValues { lots -> lots.any { it.sellPrice == null || it.sellPrice == 0.0 || it.sellDate.isNullOrEmpty() } }
        .keys
        .toList()

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortfolioListViewModel @Inject constructor(
    private val portfolioRepository: PortfolioRepository,
    private val positionRepository: PositionRepository,
    private val manualPriceRepository: ManualPriceRepository,
    private val divTaxOverrideRepository: DivTaxOverrideRepository,
    private val settingsRepository: SettingsRepository,
    private val dailyAnchorRepository: DailyAnchorRepository,
    private val quoteRepository: QuoteRepository,
    private val dividendRepository: DividendRepository,
    private val fxRateRepository: FxRateRepository,
    private val importRepository: ImportRepository,
    private val conflictCenter: ConflictCenter,
    private val syncCoordinator: SyncCoordinator,
) : ViewModel() {

    private val activePortfolioId = MutableStateFlow<String?>(null)
    private val showClosed = MutableStateFlow(false)

    /** Bumped by [refresh] so quotes/FX re-fetch on resume even when the position list itself didn't change. */
    private val refreshTick = MutableStateFlow(0)
    private var lastRefreshAtMs = 0L

    /**
     * Startup gate for the app-wide loading screen. Latched: once true it never goes back,
     * so a later sync, portfolio switch or quote refresh can't tear the NavHost down again.
     */
    private val startupComplete = MutableStateFlow(false)
    private val startupTimedOut = MutableStateFlow(false)
    private val initialSyncDone = MutableStateFlow(false)

    private data class PortfolioData(
        /** Which portfolio these rows belong to — null before Room's first emission for it. */
        val portfolioId: String?,
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
        val divsSettled: Set<String>,
        val conflicts: List<PendingConflict>,
        val rates: Map<String, Double>,
        val anchors: Map<String, Anchor>,
        val fxAnchors: Map<String, Double>,
    )

    private val portfolioData = activePortfolioId.flatMapLatest { id ->
        if (id == null) {
            flowOf(PortfolioData(null, emptyList(), emptyMap(), emptyMap()))
        } else {
            combine(
                positionRepository.observe(id),
                manualPriceRepository.observe(id),
                divTaxOverrideRepository.observe(id),
            ) { positions, manualPrices, overrides -> PortfolioData(id, positions, manualPrices, overrides) }
        }
    }

    private val quoteState = combine(quoteRepository.quotes, quoteRepository.loading, quoteRepository.errors) { q, l, e ->
        QuoteState(q, l, e)
    }

    init {
        // Fetch live quotes for every ticker with at least one open lot whenever the position set
        // changes, or a resume refresh asks for fresh prices (QuoteRepository's 60 s cache absorbs
        // any overlap).
        viewModelScope.launch {
            combine(portfolioData, refreshTick) { data, _ -> data }.collectLatest { data ->
                val openTickers = openTickersOf(data.positions)
                if (openTickers.isNotEmpty()) quoteRepository.fetchTickers(openTickers)
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
        viewModelScope.launch { refreshTick.collect { fxRateRepository.refresh() } }
        // Midnight anchors for today's change. Cached per ticker per local date, so this is a
        // no-op after the first pass of the day and costs nothing on a resume refresh.
        viewModelScope.launch {
            combine(portfolioData, quoteState, settingsRepository.settings) { data, quotes, settings ->
                Triple(openTickersOf(data.positions), quotes, settings)
            }.collectLatest { (tickers, quotes, settings) ->
                if (tickers.isEmpty()) return@collectLatest
                val currencies = tickers
                    .mapNotNull { quotes.quotes[it.uppercase()]?.currency }
                    .distinct()
                    .filterNot { it.equals(settings.displayCurrency, ignoreCase = true) }
                runCatching {
                    dailyAnchorRepository.refresh(
                        tickers = tickers,
                        currencies = currencies,
                        displayCurrency = settings.displayCurrency,
                        zone = settings.effectiveZoneId(),
                    )
                }
            }
        }
        viewModelScope.launch {
            delay(STARTUP_TIMEOUT_MS)
            startupTimedOut.value = true
        }
        // Initial sync starts here rather than waiting for PortfolioListRoute's ON_RESUME: the
        // route isn't composed until the loading screen clears, and on a fresh install the
        // loading screen is itself waiting for this pull to bring down the portfolio list.
        refresh(force = true)
    }

    val uiState: StateFlow<PortfolioListUiState> = combine(
        portfolioRepository.observe(),
        activePortfolioId,
        combine(
            portfolioData, quoteState,
            combine(dividendRepository.dividends, dividendRepository.settled) { d, s -> d to s },
            conflictCenter.pending,
            combine(
                fxRateRepository.rates,
                dailyAnchorRepository.anchors,
                dailyAnchorRepository.fxAnchors,
            ) { rates, anchors, fxAnchors -> Triple(rates, anchors, fxAnchors) },
        ) { data, quotes, (divs, divsSettled), conflicts, ratesAndAnchors ->
            val (rates, anchors, fxAnchors) = ratesAndAnchors
            CombinedState(data, quotes, divs, divsSettled, conflicts, rates, anchors, fxAnchors)
        },
        showClosed,
        combine(settingsRepository.settings, startupComplete, startupTimedOut, initialSyncDone) { settings, done, timedOut, synced ->
            StartupFlags(settings, done, timedOut, synced)
        },
    ) { portfolios, activeId, combined, closedVisible, flags ->
        val (data, quotes, divs, divsSettled, conflicts, rates) = combined
        val anchors = combined.anchors
        val fxAnchors = combined.fxAnchors
        val settings = flags.settings
        val resolvedActiveId = activeId ?: portfolios.firstOrNull()?.id
        if (activeId == null && resolvedActiveId != null) activePortfolioId.value = resolvedActiveId

        // Rows are only trustworthy once Room has emitted for the portfolio actually selected —
        // before that (startup, or right after a switch) they belong to nobody / the old portfolio.
        val dataReady = resolvedActiveId == null || data.portfolioId == resolvedActiveId
        val isLoading = !flags.startupComplete && !flags.timedOut && !startupReady(
            portfolios.isEmpty(), flags.initialSyncDone, dataReady, data.positions, quotes, divsSettled,
        )
        if (!isLoading && !flags.startupComplete) startupComplete.value = true

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
                    anchorPrice = anchors[key]?.price,
                    lastTradedAt = anchors[key]?.lastTradedAt,
                    anchorFx = quotes.quotes[key]?.currency?.let { cur ->
                        fxAnchors["$cur->${settings.displayCurrency}"]
                            ?: if (cur.equals(settings.displayCurrency, true)) 1.0 else null
                    },
                    displayCurrency = settings.displayCurrency,
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
            isLoading = isLoading,
            isSwitchingPortfolio = !dataReady,
            lastSyncedAt = settings.lastSyncedAt,
            conflictCount = conflicts.size,
            displayCurrency = settings.displayCurrency,
            rates = rates,
            dividendsByTicker = divs,
            divTaxOverrides = data.divTaxOverrides,
            portfolioIrr = portfolioIrr,
            sortOrder = settings.sortOrder,
        )
    }
        // Row derivation + portfolio XIRR re-run on every quote arrival; keep them off the main thread.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioListUiState())

    private data class StartupFlags(
        val settings: AppSettings,
        val startupComplete: Boolean,
        val timedOut: Boolean,
        val initialSyncDone: Boolean,
    )

    /**
     * True once the first screen can be shown complete: the active portfolio's lots are loaded
     * and every open ticker's first quote fetch — and every ticker's first dividend fetch — has
     * finished (success or error). An empty local DB waits for the initial sync so a fresh
     * install doesn't flash the "no positions" state before the server's data arrives.
     */
    private fun startupReady(
        noPortfolios: Boolean,
        initialSyncDone: Boolean,
        dataReady: Boolean,
        positions: List<Position>,
        quotes: QuoteState,
        divsSettled: Set<String>,
    ): Boolean {
        if (noPortfolios) return initialSyncDone
        if (!dataReady) return false
        if (positions.isEmpty() && !initialSyncDone) return false
        val quotesSettled = openTickersOf(positions)
            .map { it.uppercase() }
            .filterNot { it in NO_FEED_TICKERS }
            .all { it in quotes.quotes || it in quotes.errors }
        val divsDone = positions.map { it.ticker.uppercase() }.distinct()
            .filterNot { it in NO_FEED_TICKERS }
            .all { it in divsSettled }
        return quotesSettled && divsDone
    }

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
            PortfolioListAction.Refresh -> refresh(force = true)
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
            is PortfolioListAction.SetDisplayCurrency -> viewModelScope.launch {
                settingsRepository.setDisplayCurrency(action.currency)
            }
            is PortfolioListAction.SetSort -> viewModelScope.launch {
                val current = uiState.value.sortOrder
                // Tapping the chip that is already active means "flip direction" — a separate
                // direction control would cost a row of screen for one bit of state.
                val next = if (current.field == action.field) {
                    current.copy(ascending = !current.ascending)
                } else {
                    // A-Z reads naturally for a name; every other field is most useful largest
                    // first, which is also what the list showed before sorting existed.
                    SortOrder(action.field, ascending = action.field == SortField.NAME)
                }
                settingsRepository.setSortOrder(next)
            }
        }
    }

    /** Null when no portfolio is active — the caller (Route) skips the export flow in that case. */
    suspend fun exportActivePortfolio(): String? {
        val portfolioId = activePortfolioId.value ?: return null
        return importRepository.exportPortfolio(portfolioId)
    }

    fun onEnterForeground() = refresh(force = false)

    private fun refresh(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAtMs < RESUME_REFRESH_MIN_INTERVAL_MS) return
        lastRefreshAtMs = now
        refreshTick.update { it + 1 }
        viewModelScope.launch {
            try {
                // Pulls every portfolio, not just the active one — see
                // SyncCoordinator.pullAllPortfolios for why that matters.
                syncCoordinator.pullAllPortfolios()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline / server down: fall through with local data rather than hold the loading screen.
            } finally {
                initialSyncDone.value = true
            }
        }
    }
}
