package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LayoutAssertionsTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun clippedText_fails() {
        composeTestRule.setContent {
            Box(Modifier.width(20.dp)) {
                Text("a very long string that cannot possibly fit", maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
        assertThrows(AssertionError::class.java) { composeTestRule.assertNoClippedText() }
    }

    @Test
    fun ellipsisedText_passes() {
        composeTestRule.setContent {
            Box(Modifier.width(20.dp)) {
                Text("a very long string that cannot possibly fit", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        composeTestRule.assertNoClippedText()
    }

    @Test
    fun fittingText_passesBothChecks() {
        composeTestRule.setContent { Text("Import") }
        composeTestRule.assertNoClippedText()
        composeTestRule.assertTextNotTruncated("Import")
    }

    @Test
    fun truncatedText_failsStrictCheck() {
        // Long enough that it cannot fit even under Robolectric's stub font, which measures
        // roughly one pixel per character — an 18-character string fits a 20.dp box here.
        val long = "a very long string that cannot possibly fit"
        composeTestRule.setContent {
            Box(Modifier.width(20.dp)) {
                Text(long, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        assertThrows(AssertionError::class.java) { composeTestRule.assertTextNotTruncated(long) }
    }
}
