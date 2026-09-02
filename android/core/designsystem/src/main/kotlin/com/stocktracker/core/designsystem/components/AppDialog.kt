package com.stocktracker.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import com.stocktracker.core.designsystem.ComponentRadius
import com.stocktracker.core.designsystem.Spacing

/**
 * Drop-in replacement for M3 [androidx.compose.material3.AlertDialog] with this app's card/pill
 * chrome (rounded [ComponentRadius.dialog] surface on [MaterialTheme.colorScheme.surface])
 * instead of default M3 dialog styling. Same named-parameter shape as AlertDialog's commonly
 * used subset, so callers migrate by swapping the function name only.
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(ComponentRadius.dialog),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(Spacing.xl)) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                text?.let {
                    Spacer(Modifier.height(Spacing.md))
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                }
                Spacer(Modifier.height(Spacing.lg))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
