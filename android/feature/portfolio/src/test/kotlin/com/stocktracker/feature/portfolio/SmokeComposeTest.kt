package com.stocktracker.feature.portfolio

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.stocktracker.core.designsystem.StockTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmokeComposeTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun rendersText() {
        composeTestRule.setContent {
            StockTrackerTheme { Text("hello") }
        }
        composeTestRule.onNodeWithText("hello").assertExists()
    }
}
