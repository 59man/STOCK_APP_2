package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stocktracker.core.designsystem.components.AppDialog
import com.stocktracker.core.model.Position

private val EDIT_LOT_CURRENCIES = listOf("USD", "EUR", "GBP", "JPY", "CZK", "CHF", "CAD", "AUD")

/** Edits one purchase lot in place — field-for-field mirror of AddPositionModal's lot edit row in the web app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditLotDialog(position: Position, onDismiss: () -> Unit, onSave: (Position) -> Unit) {
    var quantity by remember { mutableStateOf(position.quantity.toString()) }
    var buyPrice by remember { mutableStateOf(position.buyPrice.toString()) }
    var buyDate by remember { mutableStateOf(position.buyDate) }
    var currency by remember { mutableStateOf(position.currency) }
    var broker by remember { mutableStateOf(position.broker ?: "") }
    var isClosed by remember { mutableStateOf(position.sellDate != null && position.sellPrice != null) }
    var sellDate by remember { mutableStateOf(position.sellDate ?: position.buyDate) }
    var sellPrice by remember { mutableStateOf(position.sellPrice?.toString() ?: "") }

    val qty = quantity.toDoubleOrNull() ?: 0.0
    val price = buyPrice.toDoubleOrNull() ?: 0.0
    val canSave = qty > 0 && price >= 0 && buyDate.isNotBlank() && (!isClosed || sellPrice.toDoubleOrNull() != null)

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit lot — ${position.ticker}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(buyDate, { buyDate = it }, label = { Text("Buy date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantity") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        buyPrice, { buyPrice = it }, label = { Text("Buy price / share") },
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    var currencyExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = currencyExpanded, onExpandedChange = { currencyExpanded = it },
                        modifier = Modifier.weight(0.6f),
                    ) {
                        OutlinedTextField(
                            value = currency, onValueChange = {}, readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        DropdownMenu(expanded = currencyExpanded, onDismissRequest = { currencyExpanded = false }) {
                            EDIT_LOT_CURRENCIES.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { currency = c; currencyExpanded = false }) }
                        }
                    }
                }

                OutlinedTextField(broker, { broker = it }, label = { Text("Broker") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isClosed, onCheckedChange = { isClosed = it })
                    Text("Closed (sold)")
                }
                if (isClosed) {
                    OutlinedTextField(sellDate, { sellDate = it }, label = { Text("Sell date") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(sellPrice, { sellPrice = it }, label = { Text("Sell price") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        position.copy(
                            quantity = qty,
                            buyPrice = price,
                            buyDate = buyDate,
                            currency = currency,
                            broker = broker.ifBlank { null },
                            sellDate = if (isClosed) sellDate else null,
                            sellPrice = if (isClosed) sellPrice.toDoubleOrNull() else null,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
