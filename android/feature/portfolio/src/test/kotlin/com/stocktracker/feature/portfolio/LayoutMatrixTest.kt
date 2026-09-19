package com.stocktracker.feature.portfolio

import androidx.compose.ui.test.junit4.createComposeRule
import com.stocktracker.core.designsystem.StockTrackerTheme
import com.stocktracker.core.model.SortOrder
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

    private fun applyConfig() {
        org.robolectric.RuntimeEnvironment.setQualifiers("+$qualifiers")
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
