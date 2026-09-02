package com.stocktracker.feature.portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.stocktracker.core.designsystem.StockTrackerTheme
import com.stocktracker.core.designsystem.components.MetricBlock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetricBlockTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun largeValue_rendersFullyOnOneLine() {
        composeTestRule.setContent {
            StockTrackerTheme { MetricBlock(label = "P&L", value = "1,000,000.00") }
        }
        // The full formatted string must exist as a single displayed node — if it wrapped
        // or got clipped, this exact node wouldn't be found.
        composeTestRule.onNodeWithText("1,000,000.00").assertExists().assertIsDisplayed()
    }
}
