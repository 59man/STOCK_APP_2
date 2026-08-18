package com.stocktracker.feature.portfolio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stocktracker.core.designsystem.StockTrackerColors
import com.stocktracker.core.model.PortfolioRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

@Composable
fun PortfolioListRoute(
    onOpenSettings: () -> Unit,
    onOpenImport: (String?) -> Unit,
    onOpenConflicts: () -> Unit,
    viewModel: PortfolioListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var sellTarget by remember { mutableStateOf<PortfolioRow?>(null) }
    var manualPriceTarget by remember { mutableStateOf<PortfolioRow?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onEnterForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = viewModel.exportActivePortfolio() ?: return@launch
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            }
        }
    }

    PortfolioListScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onOpenSettings = onOpenSettings,
        onOpenImport = onOpenImport,
        onOpenConflicts = onOpenConflicts,
        onExport = { exportLauncher.launch("portfolio_${LocalDate.now()}.json") },
        onAddPosition = { showAddDialog = true },
        onSellPosition = { sellTarget = it },
        onSetManualPrice = { manualPriceTarget = it },
    )

    if (showAddDialog && uiState.activePortfolioId != null) {
        AddPositionDialog(
            portfolioId = uiState.activePortfolioId!!,
            onDismiss = { showAddDialog = false },
            onAdded = { showAddDialog = false },
        )
    }
    if (manualPriceTarget != null && uiState.activePortfolioId != null) {
        val row = manualPriceTarget!!
        ManualPriceDialog(
            portfolioId = uiState.activePortfolioId!!,
            ticker = row.ticker,
            quantity = row.totalQuantity,
            currentManual = if (row.priceIsManual) com.stocktracker.core.model.ManualPriceEntry(row.currentPrice, row.manualPriceDate ?: "") else null,
            onDismiss = { manualPriceTarget = null },
            onDone = { manualPriceTarget = null },
        )
    }
    sellTarget?.let { row ->
        SellPositionDialog(
            row = row,
            onDismiss = { sellTarget = null },
            onConfirm = { sellPrice, sellDate ->
                viewModel.onAction(PortfolioListAction.SellPositions(row.ids, sellPrice, sellDate))
                sellTarget = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortfolioListScreen(
    uiState: PortfolioListUiState,
    onAction: (PortfolioListAction) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImport: (String?) -> Unit,
    onOpenConflicts: () -> Unit,
    onExport: () -> Unit,
    onAddPosition: () -> Unit,
    onSellPosition: (PortfolioRow) -> Unit,
    onSetManualPrice: (PortfolioRow) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stock Tracker") },
                actions = {
                    TextButton(onClick = onExport, enabled = uiState.activePortfolioId != null) { Text("Export") }
                    TextButton(onClick = { onOpenImport(uiState.activePortfolioId) }) { Text("Import") }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddPosition) { Icon(Icons.Filled.Add, contentDescription = "Add position") }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (uiState.conflictCount > 0) {
                Surface(
                    color = StockTrackerColors.Loss.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenConflicts() },
                ) {
                    Text(
                        "⚠ ${uiState.conflictCount} sync conflict${if (uiState.conflictCount == 1) "" else "s"} — tap to review",
                        color = StockTrackerColors.Loss,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            PortfolioTabs(uiState, onAction)
            SummaryHeader(uiState.visibleRows, uiState.displayCurrency, uiState.rates)

            if (uiState.closedCount > 0) {
                TextButton(onClick = { onAction(PortfolioListAction.ToggleShowClosed) }) {
                    Text(if (uiState.showClosed) "Hide closed" else "Show closed (${uiState.closedCount})")
                }
            }

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.visibleRows, key = { it.ticker }) { row ->
                        PositionCard(
                            row = row,
                            displayCurrency = uiState.displayCurrency,
                            rates = uiState.rates,
                            dividendsByTicker = uiState.dividendsByTicker,
                            divTaxOverrides = uiState.divTaxOverrides,
                            onSell = { onSellPosition(row) },
                            onDelete = { row.ids.forEach { id -> onAction(PortfolioListAction.DeletePosition(id)) } },
                            onSetManualPrice = { onSetManualPrice(row) },
                            onUpdatePosition = { position -> onAction(PortfolioListAction.UpdatePosition(position)) },
                            onSetDivTax = { ticker, date, rate -> onAction(PortfolioListAction.SetDivTax(ticker, date, rate)) },
                            onClearDivTax = { ticker, date -> onAction(PortfolioListAction.ClearDivTax(ticker, date)) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PortfolioTabs(uiState: PortfolioListUiState, onAction: (PortfolioListAction) -> Unit) {
    var showAddDialog by remember { mutableStateOf(false) }
    var manageTarget by remember { mutableStateOf<com.stocktracker.core.model.Portfolio?>(null) }
    var renameTarget by remember { mutableStateOf<com.stocktracker.core.model.Portfolio?>(null) }
    var deleteTarget by remember { mutableStateOf<com.stocktracker.core.model.Portfolio?>(null) }

    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(uiState.portfolios, key = { it.id }) { portfolio ->
            val active = portfolio.id == uiState.activePortfolioId
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(2.dp),
            ) {
                Text(
                    text = portfolio.name,
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onAction(PortfolioListAction.SwitchPortfolio(portfolio.id)) },
                            onLongClick = { manageTarget = portfolio },
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(2.dp),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Add portfolio",
                    modifier = Modifier
                        .clickable { showAddDialog = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (showAddDialog) {
        AddPortfolioDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name ->
                onAction(PortfolioListAction.AddPortfolio(name))
                showAddDialog = false
            },
        )
    }

    manageTarget?.let { portfolio ->
        AlertDialog(
            onDismissRequest = { manageTarget = null },
            title = { Text(portfolio.name) },
            text = { Text("Manage this portfolio.") },
            confirmButton = {
                TextButton(onClick = { renameTarget = portfolio; manageTarget = null }) { Text("Rename") }
            },
            dismissButton = {
                Row {
                    if (uiState.portfolios.size > 1) {
                        TextButton(
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = StockTrackerColors.Loss),
                            onClick = { deleteTarget = portfolio; manageTarget = null },
                        ) { Text("Delete") }
                    }
                    TextButton(onClick = { manageTarget = null }) { Text("Close") }
                }
            },
        )
    }

    renameTarget?.let { portfolio ->
        var name by remember(portfolio.id) { mutableStateOf(portfolio.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename portfolio") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    onAction(PortfolioListAction.RenamePortfolio(portfolio.id, name.trim()))
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { portfolio ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${portfolio.name}\"?") },
            text = { Text("This removes the portfolio and all its positions from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = StockTrackerColors.Loss),
                    onClick = { onAction(PortfolioListAction.DeletePortfolio(portfolio.id)); deleteTarget = null },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddPortfolioDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New portfolio") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SummaryHeader(rows: List<PortfolioRow>, displayCurrency: String, rates: Map<String, Double>) {
    fun dc(amount: Double, from: String) = com.stocktracker.core.calc.convert(amount, from, displayCurrency, rates)

    val totalValue = rows.sumOf { dc(it.currentValue, it.currency) }
    val totalCostBasis = rows.sumOf { dc(it.costBasis, it.currency) }
    val totalPnl = rows.sumOf { dc(it.pnl, it.currency) }
    val totalReturn = rows.sumOf { dc(it.totalReturn, it.currency) }
    val returnPercent = if (totalCostBasis > 0) (totalReturn / totalCostBasis) * 100 else 0.0

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "PORTFOLIO VALUE · $displayCurrency",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                )
                Text(
                    formatMoney(totalValue),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        "${if (totalReturn >= 0) "▲" else "▼"} ${formatMoney(totalReturn)} (${formatPercent(returnPercent)})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = pnlColor(totalReturn),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SummaryCard("P&L", formatMoney(totalPnl), Modifier.weight(1f), color = pnlColor(totalPnl))
            SummaryCard("Total return", formatMoney(totalReturn), Modifier.weight(1f), color = pnlColor(totalReturn))
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier, color: Color? = null) {
    Card(
        modifier = modifier,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color ?: MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PositionCard(
    row: PortfolioRow,
    displayCurrency: String,
    rates: Map<String, Double>,
    dividendsByTicker: Map<String, List<com.stocktracker.core.model.DividendEvent>>,
    divTaxOverrides: Map<String, Double>,
    onSell: () -> Unit,
    onDelete: () -> Unit,
    onSetManualPrice: () -> Unit,
    onUpdatePosition: (com.stocktracker.core.model.Position) -> Unit,
    onSetDivTax: (String, String, Double) -> Unit,
    onClearDivTax: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<com.stocktracker.core.model.Position?>(null) }
    var editTickerTarget by remember { mutableStateOf(false) }
    fun dc(amount: Double) = com.stocktracker.core.calc.convert(amount, row.currency, displayCurrency, rates)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Row {
                        Text(row.ticker, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (row.isClosed) {
                            Surface(color = Color.Gray, shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(start = 6.dp)) {
                                Text("SOLD", modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                        Text(
                            if (expanded) " ▾" else " ▸",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(row.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatMoney(dc(row.currentValue)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        formatMoney(dc(row.dailyChange)),
                        style = MaterialTheme.typography.bodySmall,
                        color = pnlColor(row.dailyChange),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "P&L ${formatMoney(dc(row.pnl))} (${formatPercent(row.pnlPercent)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = pnlColor(row.pnl),
                )
                Text(
                    "Return ${formatMoney(dc(row.totalReturn))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = pnlColor(row.totalReturn),
                )
            }
            row.irr?.let { irr ->
                Text(
                    "IRR ${formatPercent(irr * 100)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!row.isClosed) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    val neutralButtonColors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onSetManualPrice, colors = neutralButtonColors) {
                        Text(if (row.priceIsManual) "M ${'$'}" else "Set price")
                    }
                    TextButton(onClick = onSell, colors = neutralButtonColors) { Text("Sell") }
                    TextButton(
                        onClick = onDelete,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = StockTrackerColors.Loss,
                        ),
                    ) { Text("Delete") }
                }
            }
            if (expanded) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { editTickerTarget = true },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    ) { Text("✎ Edit ticker/name/ISIN") }
                }
                Column(Modifier.fillMaxWidth()) {
                    row.positions.forEach { lot ->
                        LotRow(lot, onEdit = { editTarget = lot })
                    }
                }
                DividendPanel(
                    row = row,
                    dividendsByTicker = dividendsByTicker,
                    taxOverrides = divTaxOverrides,
                    displayCurrency = displayCurrency,
                    rates = rates,
                    onSetDivTax = onSetDivTax,
                    onClearDivTax = onClearDivTax,
                )
            }
        }
    }

    editTarget?.let { lot ->
        EditLotDialog(
            position = lot,
            onDismiss = { editTarget = null },
            onSave = { updated -> onUpdatePosition(updated); editTarget = null },
        )
    }

    if (editTickerTarget) {
        EditTickerDialog(
            row = row,
            onDismiss = { editTickerTarget = false },
            onSave = { updatedLots ->
                updatedLots.forEach { onUpdatePosition(it) }
                editTickerTarget = false
            },
        )
    }
}

@Composable
private fun LotRow(lot: com.stocktracker.core.model.Position, onEdit: () -> Unit) {
    val isSold = lot.sellDate != null && lot.sellPrice != null
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "${lot.buyDate} · ${formatQty(lot.quantity)} @ ${formatMoney(lot.buyPrice)} ${lot.currency}",
                style = MaterialTheme.typography.bodySmall,
            )
            val sub = buildString {
                lot.broker?.let { append(it) }
                if (isSold) {
                    if (isNotEmpty()) append(" · ")
                    append("sold ${lot.sellDate} @ ${formatMoney(lot.sellPrice ?: 0.0)}")
                }
            }
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(
            onClick = onEdit,
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        ) { Text("Edit") }
    }
}

private fun formatQty(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(Locale.US, "%.4f", value)

/** Mirrors PortfolioTable.tsx's dividend events panel — per-event gross/net with an editable withholding-tax rate. */
@Composable
private fun DividendPanel(
    row: PortfolioRow,
    dividendsByTicker: Map<String, List<com.stocktracker.core.model.DividendEvent>>,
    taxOverrides: Map<String, Double>,
    displayCurrency: String,
    rates: Map<String, Double>,
    onSetDivTax: (String, String, Double) -> Unit,
    onClearDivTax: (String, String) -> Unit,
) {
    fun isRelevant(lot: com.stocktracker.core.model.Position, date: String): Boolean {
        val sellDate = lot.sellDate
        return lot.buyDate <= date && (sellDate == null || sellDate > date)
    }

    val tickerDivs = dividendsByTicker[row.ticker.uppercase()] ?: emptyList()
    val relevantDivs = tickerDivs.filter { div -> row.positions.any { lot -> isRelevant(lot, div.date) } }
    if (relevantDivs.isEmpty()) return

    var editTarget by remember { mutableStateOf<com.stocktracker.core.model.DividendEvent?>(null) }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            "Dividends received",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        relevantDivs.forEach { div ->
            val shares = row.positions.filter { lot -> isRelevant(lot, div.date) }.sumOf { it.quantity }
            val overrideKey = "${row.ticker.uppercase()}::${div.date}"
            val defaultRate = com.stocktracker.core.calc.getDividendTaxRate(row.ticker)
            val appliedRate = taxOverrides[overrideKey] ?: defaultRate
            val isOverridden = overrideKey in taxOverrides
            val gross = shares * div.amount
            val net = gross * (1 - appliedRate)
            val netDc = com.stocktracker.core.calc.convert(net, div.currency, displayCurrency, rates)

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(div.date, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${formatQty(shares)} sh × ${String.format(Locale.US, "%.4f", div.amount)} ${div.currency}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatPercent(appliedRate * 100),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOverridden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { editTarget = div }.padding(end = 12.dp),
                    )
                    Text(formatMoney(netDc), style = MaterialTheme.typography.bodySmall, color = StockTrackerColors.Gain)
                }
            }
        }
    }

    editTarget?.let { div ->
        val overrideKey = "${row.ticker.uppercase()}::${div.date}"
        DivTaxEditDialog(
            ticker = row.ticker,
            date = div.date,
            currentRate = taxOverrides[overrideKey],
            defaultRate = com.stocktracker.core.calc.getDividendTaxRate(row.ticker),
            onSave = { rate -> onSetDivTax(row.ticker, div.date, rate); editTarget = null },
            onClear = { onClearDivTax(row.ticker, div.date); editTarget = null },
            onDismiss = { editTarget = null },
        )
    }
}

@Composable
private fun DivTaxEditDialog(
    ticker: String,
    date: String,
    currentRate: Double?,
    defaultRate: Double,
    onSave: (Double) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(String.format(Locale.US, "%.1f", (currentRate ?: defaultRate) * 100)) }
    val pct = text.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tax rate — $ticker $date") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Tax %") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = pct != null && pct in 0.0..100.0, onClick = { onSave((pct ?: 0.0) / 100) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (currentRate != null) TextButton(onClick = onClear) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun pnlColor(value: Double): Color = if (value < 0) StockTrackerColors.Loss else StockTrackerColors.Gain
private fun pnlColor(formatted: String): Color = if (formatted.startsWith("-")) StockTrackerColors.Loss else StockTrackerColors.Gain

private fun formatMoney(value: Double): String = String.format(Locale.US, "%,.2f", value)
private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)
