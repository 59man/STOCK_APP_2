package com.stocktracker.core.calc

import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.Quote
import com.stocktracker.core.model.SortField
import com.stocktracker.core.model.SortOrder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Android half of the web↔Android parity check. Feeds test-fixtures/parity/input.json through
 * core:calc and asserts every figure against expected.json — the golden the web suite
 * (src/utils/parity.test.ts) produces. A failure here means the two apps show different
 * numbers for the same portfolio.
 */
class ParityTest {
    @Serializable
    private data class Anchor(val price: Double, val lastTradedAt: Long? = null)

    @Serializable
    private data class Input(
        val today: String,
        val displayCurrencies: List<String>,
        val rates: Map<String, Double>,
        val positions: List<Position>,
        val quotes: Map<String, Quote>,
        val anchors: Map<String, Anchor>,
        val fxAnchors: Map<String, Double>,
        val manualPrices: Map<String, ManualPriceEntry>,
        val dividends: Map<String, List<DividendEvent>>,
        val taxOverrides: Map<String, Double>,
        val sortFields: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(System.getProperty("user.dir")).resolve("../../../test-fixtures/parity").canonicalFile
    private val input = json.decodeFromString<Input>(dir.resolve("input.json").readText())
    private val expected = json.parseToJsonElement(dir.resolve("expected.json").readText()).let { it as JsonObject }

    private fun fxMid(cur: String): Double? = if (cur == "CZK") 1.0 else input.fxAnchors[cur]

    private fun compute(display: String): JsonObject {
        val conv: Convert = { a, f, t -> convert(a, f, t, input.rates) }
        val rows = input.positions.groupBy { it.ticker }.values.map { lots ->
            val key = lots.first().ticker.uppercase()
            val quote = input.quotes[key]
            val anchor = if (quote != null) input.anchors[key] else null
            deriveRow(
                lots = lots,
                quoteRaw = quote,
                loadingRaw = false,
                errorRaw = null,
                manualRaw = input.manualPrices[key],
                dividends = input.dividends[key] ?: emptyList(),
                taxOverrides = input.taxOverrides,
                today = input.today,
                convert = conv,
                anchorPrice = anchor?.price,
                lastTradedAt = anchor?.lastTradedAt,
                anchorFx = quote?.currency?.let { cur ->
                    if (cur == display) 1.0 else {
                        val f = fxMid(cur); val t = fxMid(display)
                        if (f != null && t != null) f / t else null
                    }
                },
                displayCurrency = display,
            )
        }
        val totalValue = rows.sumOf { conv(it.currentValue, it.currency, display) }
        val daily = portfolioDailyChange(rows, totalValue)
        val irr = computePortfolioIrr(input.positions, rows, input.dividends, input.taxOverrides, display, input.today, conv)
        return buildJsonObject {
            putJsonArray("rows") {
                rows.sortedBy { it.ticker }.forEach { r ->
                    add(buildJsonObject {
                        put("ticker", r.ticker)
                        put("avgBuyPrice", r.avgBuyPrice)
                        put("currentPrice", r.currentPrice)
                        put("currentValue", r.currentValue)
                        put("costBasis", r.costBasis)
                        put("pnl", r.pnl)
                        put("dividendIncome", r.dividendIncome)
                        put("totalReturn", r.totalReturn)
                        put("irr", r.irr)
                        put("dailyChangeDisplay", r.dailyChangeDisplay)
                        put("dailyChangePercent", r.dailyChangePercent)
                        put("dailyChangeMethod", r.dailyChangeMethod)
                        put("isClosed", r.isClosed)
                    })
                }
            }
            putJsonObject("summary") {
                put("totalValue", totalValue)
                put("totalDaily", daily.change)
                put("totalDailyPercent", daily.percent)
                put("totalReturn", rows.sumOf { conv(it.totalReturn, it.currency, display) })
                put("portfolioIrr", irr)
            }
            putJsonObject("sorted") {
                input.sortFields.forEach { f ->
                    val order = SortOrder(SortField.valueOf(f), ascending = false)
                    putJsonArray(f) { rows.sortedForDisplay(order, display, input.rates).forEach { add(JsonPrimitive(it.ticker)) } }
                }
            }
        }
    }

    private val mismatches = mutableListOf<String>()

    private fun compare(actual: JsonElement?, exp: JsonElement, path: String) {
        when (exp) {
            is JsonNull -> if (actual != null && actual !is JsonNull) mismatches += "$path: $actual vs null"
            is JsonObject -> exp.forEach { (k, v) -> compare((actual as? JsonObject)?.get(k), v, "$path.$k") }
            is JsonArray -> {
                val a = actual as? JsonArray
                if (a == null || a.size != exp.size) mismatches += "$path: size ${a?.size} vs ${exp.size}"
                else exp.forEachIndexed { i, v -> compare(a[i], v, "$path[$i]") }
            }
            is JsonPrimitive -> {
                val e = exp.doubleOrNull
                val a = (actual as? JsonPrimitive)
                when {
                    exp.isString || exp.booleanOrNull != null -> if (a?.content != exp.content) mismatches += "$path: ${a?.content} vs ${exp.content}"
                    e != null -> {
                        val ad = a?.doubleOrNull
                        if (ad == null || Math.abs(ad - e) > 0.01) mismatches += "$path: $ad vs $e"
                    }
                }
            }
        }
    }

    @Test
    fun `android calc matches the web golden`() {
        assertTrue("fixture missing at $dir", dir.resolve("expected.json").exists())
        input.displayCurrencies.forEach { c -> compare(compute(c), expected.getValue(c), c) }
        if (mismatches.isNotEmpty()) fail("${mismatches.size} parity mismatches:\n" + mismatches.joinToString("\n"))
        assertEquals(input.displayCurrencies.toSet(), expected.keys)
    }
}
