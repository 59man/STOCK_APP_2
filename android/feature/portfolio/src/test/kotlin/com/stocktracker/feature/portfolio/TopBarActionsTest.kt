package com.stocktracker.feature.portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.stocktracker.core.designsystem.StockTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopBarActionsTest {
    @get:Rule val composeTestRule = createComposeRule()

    private fun setScreen(activePortfolioId: String? = "p1") {
        composeTestRule.setContent {
            StockTrackerTheme {
                PortfolioListScreen(
                    uiState = PortfolioListUiState(
                        activePortfolioId = activePortfolioId,
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
    }

    @Test
    fun importAndExport_showVisibleLabels() {
        setScreen()
        composeTestRule.onNodeWithText("Import").assertIsDisplayed()
        composeTestRule.onNodeWithText("Export").assertIsDisplayed()
    }

    @Test
    fun actionLabels_areNotTruncated() {
        setScreen()
        composeTestRule.assertTextNotTruncated("Import")
        composeTestRule.assertTextNotTruncated("Export")
    }

    @Test
    fun actionLabels_keepFullTouchTarget() {
        setScreen()
        composeTestRule.assertMinTouchTarget("Import")
        composeTestRule.assertMinTouchTarget("Export")
    }

    @Test
    fun topBar_hasNoClippedText() {
        setScreen()
        composeTestRule.assertNoClippedText()
    }
}
