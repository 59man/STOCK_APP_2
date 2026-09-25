package com.stocktracker.feature.portfolio

import androidx.compose.ui.test.junit4.createComposeRule
import com.stocktracker.core.designsystem.StockTrackerTheme
import com.stocktracker.core.calc.ReturnPeriod
import com.stocktracker.core.calc.marketDay
import com.stocktracker.core.calc.nextMarketDays
import com.stocktracker.core.model.InstrumentProfile
import com.stocktracker.core.model.SortOrder
import com.stocktracker.core.model.TradingPeriod
import com.stocktracker.core.model.TradingPeriods
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the dense parts of the list at every phone width and system font scale the app
 * realistically meets, and fails if anything is silently cut off.
 *
 * Robolectric's `qualifiers` drive the width and `fontScale` the text size, one combination
 * per parameterised run — `createComposeRule` allows a single `setContent` per test, so a loop
 * inside one test would fail on the second iteration.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp")
class LayoutMatrixTest(
    private val qualifiers: String,
    private val fontScale: Float,
) {
    @get:Rule val composeTestRule = createComposeRule()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} fontScale={1}")
        fun cases(): List<Array<Any>> = listOf(320, 360, 411).flatMap { width ->
            listOf(1.0f, 1.3f, 2.0f).map { scale ->
                arrayOf<Any>("w${width}dp-h800dp", scale)
            }
        }
    }

    /** The real portfolio holds all of this: a long name, a big number, a signed loss. */
    private val hostileRow = fakeRow(
        ticker = "EXUS.DE",
        name = "Xtrackers MSCI World ex USA UCITS ETF 1C USD",
        currentValue = 12_345_678.90,
        totalReturn = -98_765.43,
        dailyChange = -1_234.56,
    )

    private fun applyConfig(heightDp: Int = 800) {
        org.robolectric.RuntimeEnvironment.setQualifiers("+${qualifiers.substringBefore("-h")}-h${heightDp}dp")
        org.robolectric.RuntimeEnvironment.setFontScale(fontScale)
    }

    @Test
    fun positionCard_isNeverClipped() {
        applyConfig()
        composeTestRule.setContent {
            StockTrackerTheme {
                PositionCard(
                    row = hostileRow,
                    displayCurrency = "CZK",
                    rates = mapOf("CZK" to 1.0, "USD" to 23.0),
                    onOpenDetail = {},
                )
            }
        }
        composeTestRule.assertNoClippedText()
    }

    @Test
    fun sortChips_areNeverClipped() {
        applyConfig()
        composeTestRule.setContent {
            StockTrackerTheme {
                SortChips(PortfolioListUiState(sortOrder = SortOrder(), isLoading = false), {})
            }
        }
        composeTestRule.assertNoClippedText()
        composeTestRule.assertTextNotTruncated("Name")
    }

    /**
     * The Essentials grid and the market-hours blocks are the densest new UI in the app.
     *
     * Rendered on a tall surface because the real screen scrolls: on an 800dp one the section
     * simply runs off the bottom, which reads as vertical clipping without being a defect.
     */
    @Test
    fun instrumentInfo_isNeverClipped() {
        applyConfig(heightDp = 2400)
        val zone = ZoneId.of("Europe/Prague")
        val periods = TradingPeriods(
            pre = TradingPeriod(1789707600, 1789714800),
            regular = TradingPeriod(1789714800, 1789745400),
            post = TradingPeriod(1789745400, 1789756200),
        )
        val today = marketDay(periods, zone)
        composeTestRule.setContent {
            StockTrackerTheme {
                InstrumentInfoSection(
                    state = InstrumentInfoUiState(
                        profile = InstrumentProfile(
                            ticker = "EXUS.DE",
                            exchange = "NASDAQ Global Select Market",
                            instrumentType = "ETF",
                            currency = "EUR",
                            tradingPeriods = periods,
                            fiftyTwoWeekHigh = 12_345_678.90,
                            fiftyTwoWeekLow = 32.84,
                            description = "Sleduje index rozvinutých trhů mimo Spojené státy.",
                            fundFamily = "DWS Investment S.A. (ETF)",
                            expenseRatio = 0.0015,
                            totalNetAssets = 76_148_900.0,
                        ),
                        today = today,
                        nextDays = today?.let { nextMarketDays(it, 4) }.orEmpty(),
                        returns = mapOf(ReturnPeriod.D1 to -0.25, ReturnPeriod.Y1 to 123.45),
                        zone = zone,
                        loading = false,
                    ),
                    isin = "IE000YC7FPB6",
                    distributionType = "Accumulating",
                )
            }
        }
        composeTestRule.assertNoClippedText()
    }

    /** Fractional crypto-sized quantities, a big average price, two brokers, a sold lot. */
    @Test
    fun positionFacts_areNeverClipped() {
        applyConfig(heightDp = 1600)
        val lots = listOf(
            fakePosition(id = "a", quantity = 1234.5678, buyPrice = 98_765.43, buyDate = "2021-03-04", broker = "Interactive Brokers", isin = "IE00BK5BQT80"),
            fakePosition(id = "b", quantity = 0.1234, buyPrice = 12.0, buyDate = "2022-01-01", broker = "Trading 212", sellPrice = 20.0, sellDate = "2024-01-01"),
        )
        composeTestRule.setContent {
            StockTrackerTheme {
                PositionFactsSection(
                    row = fakeRow(positions = lots, costBasis = 1_234_567.0, totalReturn = -98_765.43),
                    displayCurrency = "CZK",
                    rates = mapOf("CZK" to 1.0, "USD" to 23.0),
                )
            }
        }
        composeTestRule.assertNoClippedText()
    }

    @Test
    fun topBarActions_keepTheirLabels() {
        applyConfig()
        composeTestRule.setContent {
            StockTrackerTheme {
                PortfolioListScreen(
                    uiState = PortfolioListUiState(
                        activePortfolioId = "p1",
                        rows = listOf(hostileRow),
                        isLoading = false,
                    ),
                    onAction = {},
                    onOpenImport = {},
                    onOpenConflicts = {},
                    onOpenPositionDetail = {},
                    onExport = {},
                    onAddPosition = {},
                )
            }
        }
        composeTestRule.assertTextNotTruncated("Import")
        composeTestRule.assertTextNotTruncated("Export")
        composeTestRule.assertNoClippedText()
    }
}
