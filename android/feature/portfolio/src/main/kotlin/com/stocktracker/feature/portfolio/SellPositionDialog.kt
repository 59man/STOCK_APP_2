package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.stocktracker.core.designsystem.components.AppDialog
import com.stocktracker.core.model.PortfolioRow
import java.time.LocalDate
import java.util.Locale

/** Mirrors SellPositionModal — sell date/price for the row's open lots, with a live P&L preview. */
@Composable
fun SellPositionDialog(row: PortfolioRow, onDismiss: () -> Unit, onConfirm: (sellPrice: Double, sellDate: String) -> Unit) {
    var sellPrice by remember { mutableStateOf(if (row.currentPrice > 0) String.format(Locale.US, "%.4f", row.currentPrice) else "") }
    var sellDate by remember { mutableStateOf(LocalDate.now().toString()) }

    val price = sellPrice.toDoubleOrNull() ?: 0.0
    val estimatedPnl = if (price > 0) (price - row.avgBuyPrice) * row.totalQuantity else null

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sell ${row.ticker}") },
        text = {
            Column {
                Text("${row.lots} lot(s), ${row.totalQuantity} shares", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(sellPrice, { sellPrice = it }, label = { Text("Sell price") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sellDate, { sellDate = it }, label = { Text("Sell date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                estimatedPnl?.let { pnl ->
                    Text(
                        "Estimated P&L: ${formatMoney(pnl)}",
                        color = if (pnl < 0) com.stocktracker.core.designsystem.StockTrackerColors.loss else com.stocktracker.core.designsystem.StockTrackerColors.gain,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = price > 0 && sellDate.isNotBlank(), onClick = { onConfirm(price, sellDate) }) { Text("Sell") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
