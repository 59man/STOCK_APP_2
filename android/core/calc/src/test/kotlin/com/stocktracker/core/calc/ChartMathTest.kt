package com.stocktracker.core.calc

import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import com.stocktracker.core.model.Quote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartMathTest {

    private fun position(
        ticker: String = "XYZ",
        qty: Double = 10.0,
        buyPrice: Double = 100.0,
        buyDate: String = "2024-01-01",
        currency: String = "USD",
        sellDate: String? = null,
        sellPrice: Double? = null,
    ) = Position(
        id = "id", ticker = ticker, name = "Test Co", type = PositionType.STOCK,
        quantity = qty, buyPrice = buyPrice, buyDate = buyDate, currency = currency,
        sellDate = sellDate, sellPrice = sellPrice,
    )

    // ── priceAt ─────────────────────────────────────────────────────────

    @Test
    fun `priceAt returns exact match`() {
        val h = listOf("2024-01-01" to 10.0, "2024-01-05" to 20.0)
        assertEquals(20.0, priceAt(h, "2024-01-05")!!, 1e-9)
    }

    @Test
    fun `priceAt steps back to last point before the date`() {
        val h = listOf("2024-01-01" to 10.0, "2024-01-05" to 20.0)
        assertEquals(10.0, priceAt(h, "2024-01-03")!!, 1e-9)
    }

    @Test
    fun `priceAt prefers the point after when it is strictly closer`() {
        // before is 4 days back, after is 1 day forward — after wins (afterGap <= 1 day rule)
        val h = listOf("2024-01-01" to 10.0, "2024-01-06" to 20.0)
        assertEquals(20.0, priceAt(h, "2024-01-05")!!, 1e-9)
    }

    @Test
    fun `priceAt keeps before when after is more than a day out`() {
        val h = listOf("2024-01-01" to 10.0, "2024-01-10" to 20.0)
        assertEquals(10.0, priceAt(h, "2024-01-05")!!, 1e-9)
    }

    @Test
    fun `priceAt returns null for empty history`() {
        assertNull(priceAt(emptyList(), "2024-01-01"))
    }

    // ── interpolateDaily ────────────────────────────────────────────────

    @Test
    fun `interpolateDaily ramps linearly between two knots`() {
        val out = interpolateDaily(listOf("2024-01-01" to 100.0, "2024-01-05" to 140.0))
        val byDate = out.toMap()
        assertEquals(100.0, byDate.getValue("2024-01-01"), 1e-9)
        assertEquals(110.0, byDate.getValue("2024-01-02"), 1e-9)
        assertEquals(120.0, byDate.getValue("2024-01-03"), 1e-9)
        assertEquals(130.0, byDate.getValue("2024-01-04"), 1e-9)
        assertEquals(140.0, byDate.getValue("2024-01-05"), 1e-9)
    }

    @Test
    fun `interpolateDaily passes single knot through unchanged`() {
        val out = interpolateDaily(listOf("2024-01-01" to 100.0))
        assertEquals(listOf("2024-01-01" to 100.0), out)
    }

    // ── buildEffectiveHistories ─────────────────────────────────────────

    @Test
    fun `buildEffectiveHistories synthesizes a manual-priced ticker from buy and entry knots`() {
        val result = buildEffectiveHistories(
            histories = emptyMap(),
            manualPrices = mapOf("FUND" to ManualPriceEntry(price = 120.0, updatedAt = "2024-01-05")),
            quotes = emptyMap(),
            positions = listOf(position(ticker = "FUND", buyPrice = 100.0, buyDate = "2024-01-01", currency = "EUR")),
            tickers = listOf("FUND"),
        )
        val hist = result.getValue("FUND")
        assertEquals("EUR", hist.currency)
        assertEquals(100.0, hist.points.first { it.first == "2024-01-01" }.second, 1e-9)
        assertEquals(120.0, hist.points.first { it.first == "2024-01-05" }.second, 1e-9)
    }

    @Test
    fun `buildEffectiveHistories injects live quote as todays bar on a weekday`() {
        val today = java.time.LocalDate.of(2024, 6, 5) // a Wednesday
        val result = buildEffectiveHistories(
            histories = mapOf("XYZ" to TickerChartHistory(listOf("2024-06-03" to 50.0), currency = "USD")),
            manualPrices = emptyMap(),
            quotes = mapOf("XYZ" to Quote("XYZ", 55.0, 0.0, 0.0, "USD", "Test Co", "now")),
            positions = listOf(position(ticker = "XYZ")),
            tickers = listOf("XYZ"),
            today = today,
            isWeekend = false,
        )
        val hist = result.getValue("XYZ")
        assertEquals(55.0, hist.points.last().second, 1e-9)
        assertEquals("2024-06-05", hist.points.last().first)
    }

    // ── buildPortfolioChartData ─────────────────────────────────────────

    @Test
    fun `buildPortfolioChartData tracks cost basis and price gain in the same currency`() {
        val pos = position(ticker = "XYZ", qty = 10.0, buyPrice = 100.0, buyDate = "2024-01-01", currency = "USD")
        val histories = mapOf(
            "XYZ" to TickerChartHistory(
                points = listOf("2024-01-01" to 100.0, "2024-01-10" to 110.0),
                currency = "USD",
            ),
        )
        val points = buildPortfolioChartData(
            positions = listOf(pos),
            dividendsByTicker = emptyMap(),
            effectiveHistories = histories,
            fxHistories = emptyMap(),
            range = ChartRange.ALL,
            displayCurrency = "USD",
            spotConvert = { amount, _, _ -> amount },
        )
        val last = points.last { it.date == "2024-01-10" }
        assertEquals(1000.0, last.costBasis, 1e-9)
        assertEquals(1100.0, last.currentValue, 1e-9)
        assertEquals(100.0, last.pnl, 1e-9)
    }

    @Test
    fun `buildPortfolioChartData values a lot at cost when no price is available for that date`() {
        val pos = position(ticker = "XYZ", qty = 5.0, buyPrice = 200.0, buyDate = "2024-01-01", currency = "USD")
        val points = buildPortfolioChartData(
            positions = listOf(pos),
            dividendsByTicker = emptyMap(),
            effectiveHistories = mapOf("OTHER" to TickerChartHistory(listOf("2024-01-01" to 1.0), currency = "USD")),
            fxHistories = emptyMap(),
            range = ChartRange.ALL,
            displayCurrency = "USD",
            spotConvert = { amount, _, _ -> amount },
        )
        val point = points.first { it.date == "2024-01-01" }
        assertEquals(1000.0, point.costBasis, 1e-9)
        assertEquals(1000.0, point.currentValue, 1e-9)
        assertEquals(0.0, point.pnl, 1e-9)
    }

    @Test
    fun `buildPortfolioChartData counts a dividend received while the lot was held`() {
        val pos = position(ticker = "XYZ", qty = 10.0, buyPrice = 100.0, buyDate = "2024-01-01", currency = "USD")
        val histories = mapOf(
            "XYZ" to TickerChartHistory(listOf("2024-01-01" to 100.0, "2024-02-01" to 100.0), currency = "USD"),
        )
        val divs = mapOf("XYZ" to listOf(DividendEvent(date = "2024-02-01", amount = 2.0, currency = "USD")))
        val points = buildPortfolioChartData(
            positions = listOf(pos),
            dividendsByTicker = divs,
            effectiveHistories = histories,
            fxHistories = emptyMap(),
            range = ChartRange.ALL,
            displayCurrency = "USD",
            spotConvert = { amount, _, _ -> amount },
        )
        // XYZ is unmapped -> default 15% CZ withholding rate: 10 * 2.0 * 0.85 = 17
        val point = points.first { it.date == "2024-02-01" }
        assertEquals(17.0, point.pnl, 1e-9)
    }

    @Test
    fun `buildPortfolioChartData freezes realized gain on a sold lot`() {
        val pos = position(
            ticker = "XYZ", qty = 10.0, buyPrice = 100.0, buyDate = "2024-01-01",
            currency = "USD", sellDate = "2024-03-01", sellPrice = 150.0,
        )
        val points = buildPortfolioChartData(
            positions = listOf(pos),
            dividendsByTicker = emptyMap(),
            effectiveHistories = mapOf(
                "XYZ" to TickerChartHistory(listOf("2024-01-01" to 100.0, "2024-03-01" to 150.0), currency = "USD"),
            ),
            fxHistories = emptyMap(),
            range = ChartRange.ALL,
            displayCurrency = "USD",
            spotConvert = { amount, _, _ -> amount },
        )
        val point = points.first { it.date == "2024-03-01" }
        assertEquals(500.0, point.pnl, 1e-9) // (150 - 100) * 10
        assertEquals(0.0, point.costBasis, 1e-9) // sold lots don't count toward capital deployed
        assertEquals(0.0, point.currentValue, 1e-9)
    }
}
