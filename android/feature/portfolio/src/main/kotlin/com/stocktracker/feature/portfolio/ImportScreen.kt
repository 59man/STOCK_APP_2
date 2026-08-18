package com.stocktracker.feature.portfolio

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stocktracker.core.designsystem.StockTrackerColors
import com.stocktracker.core.importer.ColumnMapping

private val IMPORT_CURRENCIES = listOf("CZK", "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD")

/** Field-for-field companion to ImportModal + ColumnMappingModal — see the Mobile Sync Blueprint, Phase 4. */
@Composable
fun ImportRoute(activePortfolioId: String?, onDone: () -> Unit, viewModel: ImportViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasCurrentPortfolio = activePortfolioId != null

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = queryFileName(context, uri) ?: uri.lastPathSegment ?: "import"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes != null) viewModel.onFilePicked(bytes, fileName, hasCurrentPortfolio)
    }

    ImportScreen(
        uiState = uiState,
        onPickFile = { pickFile.launch(arrayOf("*/*")) },
        onSetTarget = viewModel::setTarget,
        onSetNewPortfolioName = viewModel::setNewPortfolioName,
        onSetCurrencyOverride = viewModel::setCurrencyOverride,
        onUpdateMapping = viewModel::updateMapping,
        onUpdateSkipRows = viewModel::updateSkipRows,
        onUpdateDefaultCurrency = viewModel::updateDefaultCurrency,
        onUpdateDefaultBroker = viewModel::updateDefaultBroker,
        onConfirmMapping = { viewModel.confirmMapping(hasCurrentPortfolio) },
        onConfirmImport = { viewModel.confirmImport(activePortfolioId) },
        onRetry = viewModel::reset,
        onDone = onDone,
    )
}

private fun queryFileName(context: android.content.Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0) cursor.getString(idx) else null
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportScreen(
    uiState: ImportUiState,
    onPickFile: () -> Unit,
    onSetTarget: (ImportTarget) -> Unit,
    onSetNewPortfolioName: (String) -> Unit,
    onSetCurrencyOverride: (String) -> Unit,
    onUpdateMapping: (ColumnMapping) -> Unit,
    onUpdateSkipRows: (Int) -> Unit,
    onUpdateDefaultCurrency: (String) -> Unit,
    onUpdateDefaultBroker: (String) -> Unit,
    onConfirmMapping: () -> Unit,
    onConfirmImport: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Import") }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (uiState) {
                ImportUiState.Idle -> IdleContent(onPickFile)
                ImportUiState.Parsing -> ParsingContent()
                is ImportUiState.Ready -> ReadyContent(
                    state = uiState,
                    onSetTarget = onSetTarget,
                    onSetNewPortfolioName = onSetNewPortfolioName,
                    onSetCurrencyOverride = onSetCurrencyOverride,
                    onConfirmImport = onConfirmImport,
                    onCancel = onRetry,
                )
                is ImportUiState.MappingNeeded -> MappingContent(
                    state = uiState,
                    onUpdateMapping = onUpdateMapping,
                    onUpdateSkipRows = onUpdateSkipRows,
                    onUpdateDefaultCurrency = onUpdateDefaultCurrency,
                    onUpdateDefaultBroker = onUpdateDefaultBroker,
                    onConfirm = onConfirmMapping,
                    onCancel = onRetry,
                )
                is ImportUiState.Error -> ErrorContent(uiState.message, onRetry)
                is ImportUiState.Done -> DoneContent(uiState.count, onDone)
            }
        }
    }
}

@Composable
private fun IdleContent(onPickFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Import positions from a broker statement", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Supports Fio banka & Revolut PDF statements, XTB/Trading 212/Degiro exports, CSV files, and a previously exported JSON backup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickFile) { Text("Choose file") }
    }
}

@Composable
private fun ParsingContent() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("Parsing…")
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = StockTrackerColors.Loss, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun DoneContent(count: Int, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Imported $count position${if (count == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDone) { Text("Done") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyContent(
    state: ImportUiState.Ready,
    onSetTarget: (ImportTarget) -> Unit,
    onSetNewPortfolioName: (String) -> Unit,
    onSetCurrencyOverride: (String) -> Unit,
    onConfirmImport: () -> Unit,
    onCancel: () -> Unit,
) {
    val tickers = state.result.valid.map { it.ticker }.distinct()
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.fileName, style = MaterialTheme.typography.titleMedium)
        val skippedNote = if (state.result.skipped > 0) ", ${state.result.skipped} row(s) skipped" else ""
        Text("${state.result.valid.size} position(s) found$skippedNote", style = MaterialTheme.typography.bodyMedium)
        Text(
            tickers.take(8).joinToString(", ") + if (tickers.size > 8) ", …" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.hasCurrentPortfolio) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onSetTarget(ImportTarget.CURRENT) }) {
                    RadioButton(selected = state.target == ImportTarget.CURRENT, onClick = { onSetTarget(ImportTarget.CURRENT) })
                    Text("Add to current portfolio")
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onSetTarget(ImportTarget.NEW) }) {
                    RadioButton(selected = state.target == ImportTarget.NEW, onClick = { onSetTarget(ImportTarget.NEW) })
                    Text("Create new portfolio")
                }
            }
        }
        if (!state.hasCurrentPortfolio || state.target == ImportTarget.NEW) {
            OutlinedTextField(
                value = state.newPortfolioName, onValueChange = onSetNewPortfolioName,
                label = { Text("Portfolio name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.result.currencyUncertain) {
            var expanded by remember { mutableStateOf(false) }
            Text("Account currency couldn't be detected from the filename:", style = MaterialTheme.typography.bodySmall)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = state.currencyOverride, onValueChange = {}, readOnly = true, label = { Text("Account currency") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    IMPORT_CURRENCIES.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { onSetCurrencyOverride(c); expanded = false }) }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onConfirmImport) { Text("Import") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MappingContent(
    state: ImportUiState.MappingNeeded,
    onUpdateMapping: (ColumnMapping) -> Unit,
    onUpdateSkipRows: (Int) -> Unit,
    onUpdateDefaultCurrency: (String) -> Unit,
    onUpdateDefaultBroker: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val header = state.rows.firstOrNull().orEmpty()
    val m = state.mapping
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Unrecognized file — map the columns", style = MaterialTheme.typography.titleMedium)
        Text(state.fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        LazyRow(modifier = Modifier.fillMaxWidth()) {
            items(state.rows.drop(state.skipRows).take(3)) { row ->
                Text(
                    row.joinToString(" | ") { it.take(12) },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }

        OutlinedTextField(
            value = state.skipRows.toString(),
            onValueChange = { it.toIntOrNull()?.let(onUpdateSkipRows) },
            label = { Text("Skip rows (header)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        ColumnPickerRow("Ticker *", header, m.ticker) { onUpdateMapping(m.copy(ticker = it)) }
        ColumnPickerRow("Date *", header, m.date) { onUpdateMapping(m.copy(date = it)) }
        ColumnPickerRow("Quantity *", header, m.quantity) { onUpdateMapping(m.copy(quantity = it)) }
        ColumnPickerRow("Buy Price *", header, m.buyPrice) { onUpdateMapping(m.copy(buyPrice = it)) }
        ColumnPickerRow("Name", header, m.name) { onUpdateMapping(m.copy(name = it)) }
        ColumnPickerRow("ISIN", header, m.isin) { onUpdateMapping(m.copy(isin = it)) }
        ColumnPickerRow("Currency", header, m.currency) { onUpdateMapping(m.copy(currency = it)) }
        ColumnPickerRow("Broker", header, m.broker) { onUpdateMapping(m.copy(broker = it)) }
        ColumnPickerRow("Sell Date", header, m.sellDate) { onUpdateMapping(m.copy(sellDate = it)) }
        ColumnPickerRow("Sell Price", header, m.sellPrice) { onUpdateMapping(m.copy(sellPrice = it)) }

        OutlinedTextField(state.defaultCurrency, onUpdateDefaultCurrency, label = { Text("Default currency") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.defaultBroker, onUpdateDefaultBroker, label = { Text("Default broker") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(enabled = state.requiredFieldsMapped, onClick = onConfirm) { Text("Import") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnPickerRow(label: String, header: List<String>, selected: Int?, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val display = selected?.let { "Col ${it + 1}: ${header.getOrNull(it)?.take(20) ?: ""}" } ?: "— not mapped —"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("— not mapped —") }, onClick = { onSelect(null); expanded = false })
            header.indices.forEach { idx ->
                DropdownMenuItem(
                    text = { Text("Col ${idx + 1}: ${header.getOrNull(idx)?.take(24) ?: ""}") },
                    onClick = { onSelect(idx); expanded = false },
                )
            }
        }
    }
}
