package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.PositionType

/**
 * Ticker/name/type/ISIN are row-level attributes shared by every lot of a
 * ticker, so saving applies the change to all of [PortfolioRow.positions] at
 * once — mirrors PortfolioTable.tsx's ticker-edit-block (edit mode), minus
 * the live "▶ Test" fetch check and ISIN→ticker lookup button, which are
 * deferred as a smaller follow-up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTickerDialog(row: PortfolioRow, onDismiss: () -> Unit, onSave: (List<Position>) -> Unit) {
    var ticker by remember { mutableStateOf(row.ticker) }
    var name by remember { mutableStateOf(row.name) }
    var type by remember { mutableStateOf(row.type) }
    var isin by remember { mutableStateOf(row.positions.firstOrNull()?.isin ?: "") }

    val canSave = ticker.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${row.ticker}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ticker, onValueChange = { ticker = it.uppercase() },
                    label = { Text("Ticker") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                var typeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = type.name.lowercase(), onValueChange = {}, readOnly = true, label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        PositionType.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.name.lowercase()) }, onClick = { type = option; typeExpanded = false })
                        }
                    }
                }

                OutlinedTextField(
                    value = isin, onValueChange = { isin = it },
                    label = { Text("ISIN (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val updated = row.positions.map {
                        it.copy(ticker = ticker, name = name.ifBlank { ticker }, type = type, isin = isin.ifBlank { null })
                    }
                    onSave(updated)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
