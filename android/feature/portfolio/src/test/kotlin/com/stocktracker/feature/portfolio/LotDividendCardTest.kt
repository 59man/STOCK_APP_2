package com.stocktracker.feature.portfolio

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.stocktracker.core.designsystem.StockTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LotDividendCardTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun lotCard_fourDecimalPrice_rendersOnOneLine() {
        composeTestRule.setContent {
            StockTrackerTheme {
                LotCard(lot = fakePosition(quantity = 0.12345678, buyPrice = 43210.6789, currency = "CZK"), onEdit = {})
            }
        }
        composeTestRule.onNodeWithText("43,210.68 CZK").assertExists()
    }

    @Test
    fun dividendCard_largeGrossAmount_rendersOnOneLine() {
        composeTestRule.setContent {
            StockTrackerTheme {
                DividendCard(
                    div = fakeDividendEvent(amount = 2.5, currency = "USD"),
                    grossDisplay = 125_000.00,
                    appliedRatePct = 15.0,
                    isOverridden = false,
                    netDisplay = 106_250.00,
                    onEdit = {},
                )
            }
        }
        composeTestRule.onNodeWithText("125,000.00").assertExists()
    }
}
