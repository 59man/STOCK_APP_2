package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stocktracker.core.designsystem.StockTrackerColors
import com.stocktracker.core.model.PositionType
import com.stocktracker.core.network.QuoteClient
import com.stocktracker.core.network.YahooLookupClient
import com.stocktracker.core.network.mapQuoteTypeToPositionType
import kotlinx.coroutines.launch
import java.time.LocalDate

private val CURRENCIES = listOf("USD", "EUR", "GBP", "JPY", "CZK", "CHF", "CAD", "AUD")
private val BROKERS = listOf("XTB", "Revolut", "IBKR", "Fio banka", "Degiro", "Trading 212")

/** Field-for-field mirror of AddPositionModal — see the Mobile Sync Blueprint, Phase 4. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPositionDialog(portfolioId: String, onDismiss: () -> Unit, onAdded: () -> Unit, viewModel: AddPositionViewModel = hiltViewModel()) {
    var ticker by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(PositionType.STOCK) }
    var quantity by remember { mutableStateOf("") }
    var priceInput by remember { mutableStateOf("") }
    var priceIsTotal by remember { mutableStateOf(false) }
    var buyDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var currency by remember { mutableStateOf("USD") }
    var broker by remember { mutableStateOf("") }
    var isin by remember { mutableStateOf("") }
    var isClosed by remember { mutableStateOf(false) }
    var sellDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var sellPrice by remember { mutableStateOf("") }
    var fetchTest by remember { mutableStateOf<FetchTestState>(FetchTestState.Idle) }
    var tickerWasFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val qty = quantity.toDoubleOrNull() ?: 0.0
    val rawPrice = priceInput.toDoubleOrNull() ?: 0.0
    val perSharePrice = if (priceIsTotal && qty > 0) rawPrice / qty else rawPrice
    val canSubmit = ticker.isNotBlank() && qty > 0 && rawPrice > 0 && buyDate.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add position") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        ticker, { ticker = it.uppercase(); fetchTest = FetchTestState.Idle },
                        label = { Text("Ticker") }, singleLine = true,
                        modifier = Modifier.weight(1f).onFocusChanged { focusState ->
                            // Mirrors AddPositionModal's ticker-blur autofill (web) — resolves an
                            // ISIN or bare ticker to its canonical symbol + name + type via Yahoo
                            // search, same lookup EditTickerDialog's "⟲" button uses (that dialog
                            // doesn't auto-set type, matching web's PortfolioTable edit-mode parity).
                            if (tickerWasFocused && !focusState.isFocused && ticker.isNotBlank()) {
                                scope.launch {
                                    val hit = try { YahooLookupClient.lookupIsinWithName(ticker) } catch (_: Exception) { null }
                                    if (hit != null) {
                                        ticker = hit.ticker.uppercase()
                                        hit.name?.let { name = it }
                                        type = mapQuoteTypeToPositionType(hit.quoteType)
                                    }
                                }
                            }
                            tickerWasFocused = focusState.isFocused
                        },
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
                    is FetchTestState.Ok -> Text("✓ ${state.msg}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = StockTrackerColors.gain)
                    is FetchTestState.Error -> Text("✗ ${state.msg}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = StockTrackerColors.loss)
                    else -> {}
                }

                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

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

                OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantity") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        priceInput, { priceInput = it },
                        label = { Text(if (priceIsTotal) "Total price" else "Price / share") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { priceIsTotal = !priceIsTotal }) { Text(if (priceIsTotal) "total" else "/ share") }
                }
                if (priceIsTotal && qty > 0 && rawPrice > 0) {
                    Text("= ${String.format(java.util.Locale.US, "%.4f", perSharePrice)} / share", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }

                OutlinedTextField(buyDate, { buyDate = it }, label = { Text("Buy date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                var currencyExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = currencyExpanded, onExpandedChange = { currencyExpanded = it }) {
                    OutlinedTextField(
                        value = currency, onValueChange = {}, readOnly = true, label = { Text("Currency") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    DropdownMenu(expanded = currencyExpanded, onDismissRequest = { currencyExpanded = false }) {
                        CURRENCIES.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { currency = option; currencyExpanded = false }) }
                    }
                }

                OutlinedTextField(broker, { broker = it }, label = { Text("Broker") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row { BROKERS.forEach { b -> TextButton(onClick = { broker = b }) { Text(b, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) } } }

                OutlinedTextField(isin, { isin = it }, label = { Text("ISIN (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = isClosed, onCheckedChange = { isClosed = it })
                    Text("Closed position")
                }
                if (isClosed) {
                    OutlinedTextField(sellDate, { sellDate = it }, label = { Text("Sell date") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(sellPrice, { sellPrice = it }, label = { Text("Sell price") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    viewModel.addPosition(
                        portfolioId = portfolioId, ticker = ticker, name = name.ifBlank { ticker }, type = type,
                        quantity = qty, buyPrice = perSharePrice, buyDate = buyDate, currency = currency,
                        broker = broker, isin = isin, isClosed = isClosed,
                        sellPrice = sellPrice.toDoubleOrNull(), sellDate = sellDate,
                        onDone = onAdded,
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
