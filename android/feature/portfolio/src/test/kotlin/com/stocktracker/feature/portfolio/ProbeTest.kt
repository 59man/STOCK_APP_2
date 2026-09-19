package com.stocktracker.feature.portfolio

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.stocktracker.core.designsystem.StockTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProbeTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun probe() {
        composeTestRule.setContent {
            StockTrackerTheme {
                PortfolioListScreen(
                    uiState = PortfolioListUiState(activePortfolioId = "p1", rows = listOf(fakeRow()), isLoading = false),
                    onAction = {}, onOpenImport = {}, onOpenConflicts = {},
                    onOpenPositionDetail = {}, onExport = {}, onAddPosition = {},
                )
            }
        }
        composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val r = results.firstOrNull() ?: return@forEach
            val visEnd = if (r.lineCount > 0) r.getLineEnd(r.lineCount - 1, visibleEnd = true) else -1
            println("PROBE text='${r.layoutInput.text}' size=${r.size} lines=${r.lineCount} visEnd=$visEnd len=${r.layoutInput.text.length} ovfH=${r.didOverflowHeight} bounds=${node.boundsInRoot}")
        }
    }
}
