package com.stocktracker.core.data

import android.util.Log
import com.stocktracker.core.calc.DEFAULT_RATES
import com.stocktracker.core.network.FxRateClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

private val FX_PAIRS = listOf(
    "USD" to "USDCZK=X", "EUR" to "EURCZK=X", "GBP" to "GBPCZK=X", "CHF" to "CHFCZK=X",
    "JPY" to "JPYCZK=X", "CAD" to "CADCZK=X", "AUD" to "AUDCZK=X",
)

/**
 * Live CZK-based FX rates, direct from Yahoo — ported from src/hooks/useFxRates.ts.
 * Starts at [DEFAULT_RATES] and refreshes in the background; a pair that
 * fails to fetch keeps its default rather than blocking the others.
 */
@Singleton
class FxRateRepository @Inject constructor() {
    private val _rates = MutableStateFlow(DEFAULT_RATES)
    val rates: StateFlow<Map<String, Double>> = _rates

    suspend fun refresh() = coroutineScope {
        val fetches = FX_PAIRS.map { (currency, ticker) ->
            currency to async {
                runCatching { FxRateClient.fetchRate(ticker) }
                    .onFailure { Log.w("FxRates", "$currency/CZK refresh failed, keeping ${_rates.value[currency]}: ${it.message}") }
                    .getOrNull()
            }
        }
        val updates = fetches.mapNotNull { (currency, deferred) -> deferred.await()?.let { currency to it } }.toMap()
        _rates.value = mergeFxRates(_rates.value, updates)
    }
}

/**
 * Applies a refresh on top of the rates already held. A pair that failed this round keeps its
 * last good live value — rebuilding from [DEFAULT_RATES] instead snapped a failed USD back to
 * the hardcoded 25 CZK, shifting every USD amount by several percent until the next refresh.
 */
internal fun mergeFxRates(current: Map<String, Double>, updates: Map<String, Double>): Map<String, Double> =
    current + updates
