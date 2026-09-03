package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.stocktracker.core.designsystem.StockTrackerTheme
import com.stocktracker.core.designsystem.components.AppDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDialogTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun confirmButton_staysDisplayed_withTallContent() {
        composeTestRule.setContent {
            StockTrackerTheme {
                AppDialog(
                    onDismissRequest = {},
                    title = { Text("Title") },
                    text = { Text("x".repeat(2000), modifier = Modifier.fillMaxWidth().height(2000.dp)) },
                    confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                )
            }
        }
        composeTestRule.onNodeWithText("Confirm").assertIsDisplayed()
    }
}
