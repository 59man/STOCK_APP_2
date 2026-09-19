package com.stocktracker.feature.portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.stocktracker.core.designsystem.StockTrackerTheme
import com.stocktracker.core.model.SortField
import com.stocktracker.core.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SortChipsTest {
    @get:Rule val composeTestRule = createComposeRule()

    private val labels = listOf("Name", "Type", "Value", "Today", "Return")

    private fun setChips(order: SortOrder = SortOrder(), onAction: (PortfolioListAction) -> Unit = {}) {
        composeTestRule.setContent {
            StockTrackerTheme {
                SortChips(PortfolioListUiState(sortOrder = order, isLoading = false), onAction)
            }
        }
    }

    @Test
    fun allFiveFields_areShown() {
        setChips()
        labels.forEach { composeTestRule.onNodeWithText(it, substring = true).assertIsDisplayed() }
    }

    @Test
    fun tappingInactiveChip_selectsThatField() {
        var received: PortfolioListAction? = null
        setChips(SortOrder(SortField.VALUE)) { received = it }
        composeTestRule.onNodeWithText("Name", substring = true).performClick()
        assertEquals(PortfolioListAction.SetSort(SortField.NAME), received)
    }

    @Test
    fun tappingActiveChip_emitsTheSameField_soTheViewModelCanFlipDirection() {
        var received: PortfolioListAction? = null
        setChips(SortOrder(SortField.VALUE, ascending = false)) { received = it }
        composeTestRule.onNodeWithText("Value", substring = true).performClick()
        assertEquals(PortfolioListAction.SetSort(SortField.VALUE), received)
    }

    @Test
    fun onlyTheActiveChip_showsADirectionArrow() {
        setChips(SortOrder(SortField.TODAY, ascending = true))
        composeTestRule.onNodeWithText("Today ▲").assertIsDisplayed()
        composeTestRule.onNodeWithText("Value").assertIsDisplayed()
    }

    @Test
    fun chipLabels_areNotTruncated() {
        setChips()
        // The active chip renders its label plus a direction arrow, so it is asserted as the
        // string actually laid out rather than the bare label.
        composeTestRule.assertTextNotTruncated("Value \u25BC")
        (labels - "Value").forEach { composeTestRule.assertTextNotTruncated(it) }
    }

    @Test
    fun chipRow_hasNoClippedText() {
        setChips()
        composeTestRule.assertNoClippedText()
    }
}
