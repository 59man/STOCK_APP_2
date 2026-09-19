package com.stocktracker.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.core.calc.MarketDay
import com.stocktracker.core.calc.ReturnPeriod
import com.stocktracker.core.calc.marketDay
import com.stocktracker.core.calc.nextMarketDays
import com.stocktracker.core.calc.ratesOfReturn
import com.stocktracker.core.data.InstrumentProfileRepository
import com.stocktracker.core.data.SettingsRepository
import com.stocktracker.core.data.effectiveZoneId
import com.stocktracker.core.model.InstrumentProfile
import com.stocktracker.core.network.HistoryClient
import com.stocktracker.core.network.yahooChartQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject

data class InstrumentInfoUiState(
    val profile: InstrumentProfile? = null,
    val today: MarketDay? = null,
    val nextDays: List<MarketDay> = emptyList(),
    val returns: Map<ReturnPeriod, Double?> = emptyMap(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val loading: Boolean = true,
)

/**
 * Owns the instrument-metadata fetch for one ticker.
 *
 * Separate from PortfolioListViewModel on purpose: that one recomputes every row on each quote
 * arrival, and a profile fetch has nothing to do with it. This also means the detail screen can
 * render its metrics immediately while the profile is still loading.
 */
@HiltViewModel
class InstrumentInfoViewModel @Inject constructor(
    private val repository: InstrumentProfileRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InstrumentInfoUiState())
    val uiState: StateFlow<InstrumentInfoUiState> = _uiState

    private var loadedTicker: String? = null

    /** Idempotent: the detail screen calls this on every recomposition of its LaunchedEffect. */
    fun load(ticker: String) {
        if (loadedTicker == ticker) return
        loadedTicker = ticker
        viewModelScope.launch {
            val zone = settingsRepository.settings.first().effectiveZoneId()
            _uiState.value = _uiState.value.copy(zone = zone, loading = true)

            repository.refreshIfStale(ticker)
            val profile = repository.observe(ticker).first()
            val today = profile?.tradingPeriods?.let { marketDay(it, zone) }

            _uiState.value = _uiState.value.copy(
                profile = profile,
                today = today,
                nextDays = today?.let { nextMarketDays(it, 4) }.orEmpty(),
                loading = false,
            )

            // A year of daily closes is all the chips need, and it is a separate, failable
            // call — a history outage must not blank the profile that already loaded.
            val returns = runCatching {
                withContext(Dispatchers.Default) {
                    ratesOfReturn(HistoryClient.fetchHistory(ticker, "1y").points)
                }
            }.getOrDefault(emptyMap())
            _uiState.value = _uiState.value.copy(returns = returns)
        }
    }
}
