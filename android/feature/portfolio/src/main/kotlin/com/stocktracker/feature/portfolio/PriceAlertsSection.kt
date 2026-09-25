package com.stocktracker.feature.portfolio

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stocktracker.core.data.alerts.PriceAlertRepository
import com.stocktracker.core.designsystem.NumericTypography
import com.stocktracker.core.designsystem.Spacing
import com.stocktracker.core.designsystem.components.AppCard
import com.stocktracker.core.designsystem.components.AppDialog
import com.stocktracker.core.designsystem.components.ToggleChip
import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.PriceAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PriceAlertsViewModel @Inject constructor(
    private val repository: PriceAlertRepository,
) : ViewModel() {
    val alerts = repository.alerts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(ticker: String, above: Boolean, threshold: Double, currency: String) {
        viewModelScope.launch { repository.add(ticker, above, threshold, currency) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}

/**
 * Price alerts for one position, checked in the background every 15 minutes (the shortest
 * interval Android allows). Thresholds are in the row currency, the same one [PortfolioRow.currentPrice] uses.
 */
@Composable
internal fun PriceAlertsSection(row: PortfolioRow, viewModel: PriceAlertsViewModel = hiltViewModel()) {
    val all by viewModel.alerts.collectAsStateWithLifecycle()
    val alerts = all.filter { it.ticker == row.ticker.uppercase() }
    var adding by remember { mutableStateOf(false) }
    // Asked only when the first alert is created — the permission is useless without one.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    PriceAlertsContent(alerts = alerts, onAdd = { adding = true }, onDelete = viewModel::delete)

    if (adding) {
        AddAlertDialog(
            currentPrice = row.currentPrice,
            currency = row.currency,
            onDismiss = { adding = false },
            onConfirm = { above, threshold ->
                viewModel.add(row.ticker, above, threshold, row.currency)
                adding = false
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        )
    }
}

@Composable
internal fun PriceAlertsContent(alerts: List<PriceAlert>, onAdd: () -> Unit, onDelete: (String) -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Price alerts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onAdd) { Text("Add") }
        }
        if (alerts.isEmpty()) {
            Text(
                "Get a notification when the price crosses a level. Checked every 15 minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        alerts.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${if (a.above) "Above" else "Below"} ${formatMoney(a.threshold)} ${a.currency}",
                        style = NumericTypography.labelMedium,
                    )
                    if (!a.armed) {
                        Text(
                            "Triggered — re-arms when the price comes back",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { onDelete(a.id) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun AddAlertDialog(currentPrice: Double, currency: String, onDismiss: () -> Unit, onConfirm: (Boolean, Double) -> Unit) {
    var above by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf(String.format(java.util.Locale.US, "%.2f", currentPrice)) }
    val threshold = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("New price alert") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ToggleChip("Above", above) { above = true }
                    ToggleChip("Below", !above) { above = false }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Price ($currency)") },
                    singleLine = true,
                    isError = threshold == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                )
                Text(
                    "Now ${formatMoney(currentPrice)} $currency",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        },
        confirmButton = { TextButton(onClick = { threshold?.let { onConfirm(above, it) } }, enabled = threshold != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
