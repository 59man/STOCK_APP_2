package com.stocktracker.feature.portfolio

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.stocktracker.core.designsystem.StockTrackerTheme
import com.stocktracker.core.model.SortField
import com.stocktracker.core.model.SortOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden images for what assertions cannot describe: spacing, alignment, colour, a component
 * that renders blank.
 *
 * Recorded at one configuration only — 360dp, font scale 1.0, dark theme — to keep the image
 * count and the repository churn down. The size and font-scale matrix stays assertion-based in
 * [LayoutMatrixTest].
 *
 * Regenerate deliberately with `./gradlew :feature:portfolio:recordRoborazziDebug` and look at
 * the diff before committing: a golden recorded from broken UI locks the breakage in.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-night")
class GoldenScreenshotTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun positionCard() {
        composeTestRule.setContent {
            StockTrackerTheme {
                PositionCard(
                    row = fakeRow(),
                    displayCurrency = "CZK",
                    rates = mapOf("CZK" to 1.0, "USD" to 23.0),
                    onOpenDetail = {},
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/position_card.png")
    }

    @Test
    fun positionCard_longNameAndLoss() {
        composeTestRule.setContent {
            StockTrackerTheme {
                PositionCard(
                    row = fakeRow(
                        ticker = "EXUS.DE",
                        name = "Xtrackers MSCI World ex USA UCITS ETF 1C USD",
                        currentValue = 12_345_678.90,
                        totalReturn = -98_765.43,
                        dailyChange = -1_234.56,
                    ),
                    displayCurrency = "CZK",
                    rates = mapOf("CZK" to 1.0, "USD" to 23.0),
                    onOpenDetail = {},
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/position_card_long.png")
    }

    @Test
    fun sortChips() {
        composeTestRule.setContent {
            StockTrackerTheme {
                SortChips(
                    PortfolioListUiState(sortOrder = SortOrder(SortField.TODAY, ascending = true), isLoading = false),
                    {},
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/sort_chips.png")
    }

    @Test
    fun topBarActions() {
        composeTestRule.setContent {
            StockTrackerTheme {
                PortfolioListScreen(
                    uiState = PortfolioListUiState(
                        activePortfolioId = "p1",
                        rows = listOf(fakeRow()),
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
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/list_screen.png")
    }
}
