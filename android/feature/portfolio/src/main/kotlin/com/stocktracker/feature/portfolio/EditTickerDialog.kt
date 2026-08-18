package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stocktracker.core.designsystem.StockTrackerColors
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.PositionType
import com.stocktracker.core.network.QuoteClient
import com.stocktracker.core.network.YahooLookupClient
import kotlinx.coroutines.launch

/** Shared with AddPositionDialog's own "▶ Test" fetch button. */
internal sealed interface FetchTestState {
    data object Idle : FetchTestState
    data object Loading : FetchTestState
    data class Ok(val msg: String) : FetchTestState
    data class Error(val msg: String) : FetchTestState
}

/**
 * Ticker/name/type/ISIN are row-level attributes shared by every lot of a
 * ticker, so saving applies the change to all of [PortfolioRow.positions] at
 * once — mirrors PortfolioTable.tsx's ticker-edit-block (edit mode),
 * including the live "▶ Test" fetch check and the "⟲" ISIN→ticker lookup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTickerDialog(row: PortfolioRow, onDismiss: () -> Unit, onSave: (List<Position>) -> Unit) {
    var ticker by remember { mutableStateOf(row.ticker) }
    var name by remember { mutableStateOf(row.name) }
    var type by remember { mutableStateOf(row.type) }
    var isin by remember { mutableStateOf(row.positions.firstOrNull()?.isin ?: "") }
    var fetchTest by remember { mutableStateOf<FetchTestState>(FetchTestState.Idle) }
    var lookingUp by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val canSave = ticker.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${row.ticker}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ticker, onValueChange = { ticker = it.uppercase(); fetchTest = FetchTestState.Idle },
                        label = { Text("Ticker") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = ticker.isNotBlank() && fetchTest != FetchTestState.Loading,
                        onClick = {
                            fetchTest = FetchTestState.Loading
                            scope.launch {
                                fetchTest = try {
                                    val quote = QuoteClient.fetchQuote(ticker)
                                    FetchTestState.Ok("${quote.price} ${quote.currency}")
                                } catch (e: Exception) {
                                    FetchTestState.Error(e.message ?: "Failed")
                                }
                            }
                        },
                    ) { Text(if (fetchTest == FetchTestState.Loading) "…" else "▶ Test") }
                }
                when (val state = fetchTest) {
                    is FetchTestState.Ok -> Text("✓ ${state.msg}", style = MaterialTheme.typography.bodySmall, color = StockTrackerColors.Gain)
                    is FetchTestState.Error -> Text("✗ ${state.msg}", style = MaterialTheme.typography.bodySmall, color = StockTrackerColors.Loss)
                    else -> {}
                }

                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                var typeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = type.name, onValueChange = {}, readOnly = true, label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        PositionType.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.name) }, onClick = { type = option; typeExpanded = false })
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = isin, onValueChange = { isin = it.uppercase() },
                        label = { Text("ISIN (optional)") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = isin.isNotBlank() && !lookingUp,
                        onClick = {
                            lookingUp = true
                            scope.launch {
                                val hit = try { YahooLookupClient.lookupIsinWithName(isin) } catch (_: Exception) { null }
                                if (hit != null) {
                                    ticker = hit.ticker.uppercase()
                                    hit.name?.let { name = it }
                                    fetchTest = FetchTestState.Idle
                                }
                                lookingUp = false
                            }
                        },
                    ) { Text(if (lookingUp) "…" else "⟲") }
                }
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
