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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.core.data.ManualPriceRepository
import com.stocktracker.core.data.sync.SyncCoordinator
import com.stocktracker.core.data.sync.SyncTarget
import com.stocktracker.core.designsystem.components.AppDialog
import com.stocktracker.core.model.ManualPriceEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class ManualPriceViewModel @Inject constructor(
    private val manualPriceRepository: ManualPriceRepository,
    private val syncCoordinator: SyncCoordinator,
) : ViewModel() {
    fun setPrice(portfolioId: String, ticker: String, totalValue: Double, quantity: Double, onDone: () -> Unit) {
        viewModelScope.launch {
            val perUnit = if (quantity > 0) totalValue / quantity else 0.0
            manualPriceRepository.set(portfolioId, ticker, perUnit, Instant.now().toString())
            syncCoordinator.enqueuePush(portfolioId, SyncTarget.MANUAL_PRICES)
            onDone()
        }
    }

    fun clearPrice(portfolioId: String, ticker: String, onDone: () -> Unit) {
        viewModelScope.launch {
            manualPriceRepository.clear(portfolioId, ticker)
            syncCoordinator.enqueuePush(portfolioId, SyncTarget.MANUAL_PRICES)
            onDone()
        }
    }
}

/**
 * For no-feed tickers (see NO_FEED_TICKERS): the user enters the *total*
 * position value from their bank report, and price = totalValue / quantity —
 * mirroring the web app's manual price flow exactly.
 */
@Composable
fun ManualPriceDialog(
    portfolioId: String,
    ticker: String,
    quantity: Double,
    currentManual: ManualPriceEntry?,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
    viewModel: ManualPriceViewModel = hiltViewModel(),
) {
    var totalValue by remember { mutableStateOf(currentManual?.let { (it.price * quantity).toString() } ?: "") }
    val value = totalValue.toDoubleOrNull()

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual price — $ticker") },
        text = {
            Column {
                Text("Enter the total position value from your bank/broker report.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(totalValue, { totalValue = it }, label = { Text("Total value") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (value != null && quantity > 0) {
                    Text("= ${String.format(java.util.Locale.US, "%.4f", value / quantity)} / share")
                }
                currentManual?.let { Text("Last set: ${it.updatedAt}", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = value != null && value > 0 && quantity > 0, onClick = {
                viewModel.setPrice(portfolioId, ticker, value ?: 0.0, quantity, onDone)
            }) { Text("Save") }
        },
        dismissButton = {
            if (currentManual != null) {
                TextButton(onClick = { viewModel.clearPrice(portfolioId, ticker, onDone) }) { Text("Clear") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
