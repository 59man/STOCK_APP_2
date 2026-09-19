package com.stocktracker.feature.portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.stocktracker.core.calc.ReturnPeriod
import com.stocktracker.core.calc.marketDay
import com.stocktracker.core.calc.nextMarketDays
import com.stocktracker.core.designsystem.StockTrackerTheme
import com.stocktracker.core.model.InstrumentProfile
import com.stocktracker.core.model.TradingPeriod
import com.stocktracker.core.model.TradingPeriods
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Tall viewport: the whole section is one column, and on the default 414dp-high screen the
// lower cards sit below the fold, where assertIsDisplayed correctly reports them as not shown.
@Config(qualifiers = "w360dp-h1600dp")
class InstrumentInfoSectionTest {
    @get:Rule val composeTestRule = createComposeRule()

    private val periods = TradingPeriods(
        pre = TradingPeriod(1789707600, 1789714800),
        regular = TradingPeriod(1789714800, 1789745400),
        post = TradingPeriod(1789745400, 1789756200),
    )

    private val profile = InstrumentProfile(
        ticker = "EXUS.DE",
        longName = "Xtrackers MSCI World ex USA UCITS ETF 1C USD",
        exchange = "XETRA",
        instrumentType = "ETF",
        currency = "EUR",
        exchangeTimeZone = "Europe/Berlin",
        tradingPeriods = periods,
        fiftyTwoWeekHigh = 41.04,
        fiftyTwoWeekLow = 32.84,
        description = "The fund tracks an index of developed-market equities excluding the US.",
        sector = "Global Equity",
        fundFamily = "Xtrackers",
        expenseRatio = 0.0015,
    )

    private fun state(
        p: InstrumentProfile? = profile,
        zone: ZoneId = ZoneId.of("Europe/Prague"),
        returns: Map<ReturnPeriod, Double?> = mapOf(ReturnPeriod.D1 to -0.25, ReturnPeriod.Y1 to 12.5),
    ): InstrumentInfoUiState {
        val today = p?.tradingPeriods?.let { marketDay(it, zone) }
        return InstrumentInfoUiState(
            profile = p,
            today = today,
            nextDays = today?.let { nextMarketDays(it, 4) }.orEmpty(),
            returns = returns,
            zone = zone,
            loading = false,
        )
    }

    private fun setSection(state: InstrumentInfoUiState, isin: String? = "IE000YC7FPB6", distribution: String? = "Accumulating") {
        composeTestRule.setContent {
            StockTrackerTheme { InstrumentInfoSection(state, isin, distribution) }
        }
    }

    @Test fun `all four cards render when data is present`() {
        setSection(state())
        listOf("About", "Market hours", "Essentials", "Rates of return").forEach {
            composeTestRule.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun `market hours are shown in the viewer's zone, not the exchange's`() {
        setSection(state(zone = ZoneId.of("Asia/Tokyo")))
        // The Berlin 09:00-17:30 session reads 16:00-00:30 in Tokyo.
        composeTestRule.onNodeWithText("16:00 - 00:30").assertIsDisplayed()
    }

    @Test fun `the same session in Prague keeps Central European hours`() {
        setSection(state())
        composeTestRule.onNodeWithText("09:00 - 17:30").assertIsDisplayed()
    }

    @Test fun `the zone being displayed is named`() {
        setSection(state())
        composeTestRule.onNodeWithText("Hours shown in Europe/Prague.").assertIsDisplayed()
    }

    @Test fun `next days are labelled as estimates`() {
        setSection(state())
        composeTestRule.onNodeWithText("Show next days ▼").performClick()
        composeTestRule.onNodeWithText(
            "Estimated from today's hours; exchange holidays are not accounted for.",
        ).assertIsDisplayed()
    }

    @Test fun `essentials omit values the instrument does not publish`() {
        setSection(state(p = profile.copy(expenseRatio = null, category = null)))
        // DetailMetricGrid renders its labels uppercase.
        composeTestRule.onNodeWithText("EXPENSE RATIO").assertDoesNotExistCompat()
        composeTestRule.onNodeWithText("EXCHANGE").assertIsDisplayed()
    }

    @Test fun `the About card disappears when the crumb-gated fetch gave nothing`() {
        // The whole point of treating quoteSummary as best-effort: the rest still renders.
        setSection(state(p = profile.copy(description = null, sector = null, fundFamily = null, industry = null)))
        composeTestRule.onNodeWithText("About").assertDoesNotExistCompat()
        composeTestRule.onNodeWithText("Market hours").assertIsDisplayed()
        composeTestRule.onNodeWithText("Essentials").assertIsDisplayed()
    }

    @Test fun `return chips show signed percentages`() {
        setSection(state())
        composeTestRule.onNodeWithText("-0.25%").assertIsDisplayed()
        composeTestRule.onNodeWithText("+12.50%").assertIsDisplayed()
    }

    @Test fun `a period with no value has no chip`() {
        setSection(state(returns = mapOf(ReturnPeriod.D1 to 1.0, ReturnPeriod.Y1 to null)))
        composeTestRule.onNodeWithText("1Y").assertDoesNotExistCompat()
        composeTestRule.onNodeWithText("1D").assertIsDisplayed()
    }

    @Test fun `nothing renders at all before the profile loads`() {
        setSection(state(p = null))
        composeTestRule.onNodeWithText("Market hours").assertDoesNotExistCompat()
    }

    @Test fun `a session that is not today is labelled as the last one, not Today`() {
        // Yahoo's currentTradingPeriod is the most recent session, which on a Saturday is
        // Friday's. Epochs here are in 2020, so this is deterministic whenever it runs.
        val old = TradingPeriods(regular = TradingPeriod(1600412400, 1600434000))
        setSection(state(p = profile.copy(tradingPeriods = old)))
        composeTestRule.onNodeWithText("Last session", substring = true).assertIsDisplayed()
    }

    @Test fun `volume is shown as a share count, not money`() {
        setSection(state(p = profile.copy(volume = 16_090_017L)))
        composeTestRule.onNodeWithText("16,090,017").assertIsDisplayed()
    }

    @Test fun `the section has no clipped text`() {
        setSection(state())
        composeTestRule.assertNoClippedText()
    }
}
