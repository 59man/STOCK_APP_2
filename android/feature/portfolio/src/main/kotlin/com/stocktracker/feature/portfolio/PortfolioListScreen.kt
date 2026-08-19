package com.stocktracker.feature.portfolio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stocktracker.core.designsystem.NumericTypography
import com.stocktracker.core.designsystem.Spacing
import com.stocktracker.core.designsystem.StockTrackerColors
import com.stocktracker.core.designsystem.components.AppButton
import com.stocktracker.core.designsystem.components.AppButtonVariant
import com.stocktracker.core.designsystem.components.AppCard
import com.stocktracker.core.designsystem.components.Badge
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
    onOpenPositionDetail: (String) -> Unit,
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
        onOpenPositionDetail = onOpenPositionDetail,
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
    onOpenPositionDetail: (String) -> Unit,
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

            if (uiState.closedCount > 0) {
                TextButton(onClick = { onAction(PortfolioListAction.ToggleShowClosed) }) {
                    Text(if (uiState.showClosed) "Hide closed" else "Show closed (${uiState.closedCount})")
                }
            }

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (uiState.rows.isEmpty()) {
                EmptyPortfolioState(
                    onImportStatement = { onOpenImport(uiState.activePortfolioId) },
                    onAddManually = onAddPosition,
                )
            } else {
                LazyColumn(
                    // Extra bottom inset so the floating "+" button never overlaps the last
                    // card's content (it was covering the Total Return pie's title/legend).
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Scrolls away with everything else now — previously pinned above the list,
                    // the seven summary cards alone ran past half a phone screen, permanently
                    // shrinking the space left for positions.
                    item(key = "summary") {
                        SummaryHeader(uiState.visibleRows, uiState.displayCurrency, uiState.rates, uiState.portfolioIrr)
                    }
                    if (uiState.visibleRows.isNotEmpty()) {
                        item(key = "pnl-chart") { PortfolioPnlChartCard(portfolioId = uiState.activePortfolioId) }
                        item(key = "pie-charts") {
                            PortfolioPieChartsCard(uiState.visibleRows, uiState.displayCurrency, uiState.rates)
                        }
                    }
                    items(uiState.visibleRows, key = { it.ticker }) { row ->
                        PositionCard(
                            row = row,
                            displayCurrency = uiState.displayCurrency,
                            rates = uiState.rates,
                            onOpenDetail = { onOpenPositionDetail(row.ticker) },
                            onSell = { onSellPosition(row) },
                            onDelete = { row.ids.forEach { id -> onAction(PortfolioListAction.DeletePosition(id)) } },
                            onSetManualPrice = { onSetManualPrice(row) },
                        )
                    }
                }
            }
        }
    }
}

/** Shown for a brand-new portfolio with zero positions — the moment that decides whether this install ever gets used again. */
@Composable
private fun EmptyPortfolioState(onImportStatement: () -> Unit, onAddManually: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Nothing tracked yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Import a broker statement — PDF, XLSX, CSV, or a photo of a paper one — and see your whole portfolio in seconds.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onImportStatement, modifier = Modifier.fillMaxWidth(0.8f)) { Text("Import statement") }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onAddManually, modifier = Modifier.fillMaxWidth(0.8f)) { Text("Add manually") }
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
private fun SummaryHeader(rows: List<PortfolioRow>, displayCurrency: String, rates: Map<String, Double>, portfolioIrr: Double?) {
    fun dc(amount: Double, from: String) = com.stocktracker.core.calc.convert(amount, from, displayCurrency, rates)

    val totalValue = rows.sumOf { dc(it.currentValue, it.currency) }
    val totalCostBasis = rows.sumOf { dc(it.costBasis, it.currency) }
    val totalPnl = rows.sumOf { dc(it.pnl, it.currency) }
    val totalDividends = rows.sumOf { dc(it.dividendIncome, it.currency) }
    val totalReturn = rows.sumOf { dc(it.totalReturn, it.currency) }
    val returnPercent = if (totalCostBasis > 0) (totalReturn / totalCostBasis) * 100 else 0.0
    val totalDailyChange = rows.sumOf { dc(it.dailyChange, it.currency) }
    val prevTotalValue = totalValue - totalDailyChange
    val dailyChangePercent = if (prevTotalValue > 0) (totalDailyChange / prevTotalValue) * 100 else 0.0

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        AppCard(modifier = Modifier.fillMaxWidth(), contentPadding = Spacing.xl) {
            Text(
                "PORTFOLIO VALUE · $displayCurrency",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
            )
            Text(
                formatMoney(totalValue),
                style = NumericTypography.headlineEmphasis,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Row(modifier = Modifier.padding(top = Spacing.sm)) {
                Text(
                    "${if (totalReturn >= 0) "▲" else "▼"} ${formatMoney(totalReturn)} (${formatPercent(returnPercent)})",
                    style = NumericTypography.bodyMedium,
                    color = pnlColor(totalReturn),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SummaryCard("P&L", formatMoney(totalPnl), Modifier.weight(1f), color = pnlColor(totalPnl))
            SummaryCard("Total return", formatMoney(totalReturn), Modifier.weight(1f), color = pnlColor(totalReturn))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SummaryCard(
                "Today's change",
                "${if (totalDailyChange >= 0) "+" else ""}${formatMoney(totalDailyChange)} (${formatPercent(dailyChangePercent)})",
                Modifier.weight(1f),
                color = pnlColor(totalDailyChange),
            )
            SummaryCard(
                "Net dividends",
                if (totalDividends > 0) "+" + formatMoney(totalDividends) else "—",
                Modifier.weight(1f),
                color = if (totalDividends > 0) StockTrackerColors.gain else null,
            )
            SummaryCard(
                "IRR p.a.",
                if (portfolioIrr != null) formatPercent(portfolioIrr * 100) else "…",
                Modifier.weight(1f),
                color = if (portfolioIrr != null) pnlColor(portfolioIrr) else null,
            )
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier, color: Color? = null) {
    AppCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentPadding = Spacing.md,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = NumericTypography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PositionCard(
    row: PortfolioRow,
    displayCurrency: String,
    rates: Map<String, Double>,
    onOpenDetail: () -> Unit,
    onSell: () -> Unit,
    onDelete: () -> Unit,
    onSetManualPrice: () -> Unit,
) {
    fun dc(amount: Double) = com.stocktracker.core.calc.convert(amount, row.currency, displayCurrency, rates)

    AppCard(modifier = Modifier.fillMaxWidth()) {
        // Transaction/lot details, dividends, and the price chart live on their own screen now
        // (PositionDetailRoute) — cramming them inline here, under an already-tall summary
        // section, made them unreadably small on a phone.
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDetail),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Row {
                    Text(row.ticker, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (row.isClosed) {
                        Badge(
                            "SOLD",
                            modifier = Modifier.padding(start = Spacing.sm),
                            containerColor = Color.Gray,
                            contentColor = Color.White,
                        )
                    }
                    Text(
                        " ›",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(row.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatMoney(dc(row.currentValue)),
                    style = NumericTypography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    formatMoney(dc(row.dailyChange)),
                    style = NumericTypography.bodyMedium,
                    color = pnlColor(row.dailyChange),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.md), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "P&L ${formatMoney(dc(row.pnl))} (${formatPercent(row.pnlPercent)})",
                style = NumericTypography.bodyMedium,
                color = pnlColor(row.pnl),
            )
            Text(
                "Return ${formatMoney(dc(row.totalReturn))}",
                style = NumericTypography.bodyMedium,
                color = pnlColor(row.totalReturn),
            )
        }
        row.irr?.let { irr ->
            Text(
                "IRR ${formatPercent(irr * 100)}",
                style = NumericTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        if (!row.isClosed) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.End) {
                // A manually-priced ticker (no live feed) is easy to forget about and let go
                // stale — the primary accent makes "M $" visually distinct from the other two
                // neutral actions instead of blending in.
                AppButton(
                    text = if (row.priceIsManual) "M ${'$'}" else "Set price",
                    onClick = onSetManualPrice,
                    variant = if (row.priceIsManual) AppButtonVariant.Primary else AppButtonVariant.Secondary,
                    emphasized = row.priceIsManual,
                )
                AppButton(text = "Sell", onClick = onSell, variant = AppButtonVariant.Secondary)
                AppButton(text = "Delete", onClick = onDelete, variant = AppButtonVariant.Danger)
            }
        }
    }
}

/**
 * Full-screen destination for one ticker's lots, dividends, and price chart — split out of
 * PositionCard's inline expansion because that content (a lot table plus a dividend panel plus
 * a chart) was unreadable crammed into a list card on a phone. Re-derives the row from the same
 * ViewModel/uiState the list screen uses rather than passing PortfolioRow across navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionDetailRoute(
    ticker: String,
    onBack: () -> Unit,
    viewModel: PortfolioListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val row = uiState.rows.firstOrNull { it.ticker == ticker }
    var editTarget by remember { mutableStateOf<com.stocktracker.core.model.Position?>(null) }
    var editTickerTarget by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(row?.ticker ?: ticker) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (row == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
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
                    dividendsByTicker = uiState.dividendsByTicker,
                    taxOverrides = uiState.divTaxOverrides,
                    displayCurrency = uiState.displayCurrency,
                    rates = uiState.rates,
                    onSetDivTax = { t, date, rate -> viewModel.onAction(PortfolioListAction.SetDivTax(t, date, rate)) },
                    onClearDivTax = { t, date -> viewModel.onAction(PortfolioListAction.ClearDivTax(t, date)) },
                )
                PriceChartCard(
                    ticker = row.ticker,
                    tickerCurrency = row.currency,
                    displayCurrency = uiState.displayCurrency,
                    rates = uiState.rates,
                )
            }

            editTarget?.let { lot ->
                EditLotDialog(
                    position = lot,
                    onDismiss = { editTarget = null },
                    onSave = { updated -> viewModel.onAction(PortfolioListAction.UpdatePosition(updated)); editTarget = null },
                )
            }

            if (editTickerTarget) {
                EditTickerDialog(
                    row = row,
                    onDismiss = { editTickerTarget = false },
                    onSave = { updatedLots ->
                        updatedLots.forEach { viewModel.onAction(PortfolioListAction.UpdatePosition(it)) }
                        editTickerTarget = false
                    },
                )
            }
        }
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

@Composable
private fun pnlColor(value: Double): Color = if (value < 0) StockTrackerColors.loss else StockTrackerColors.gain
@Composable
private fun pnlColor(formatted: String): Color = if (formatted.startsWith("-")) StockTrackerColors.loss else StockTrackerColors.gain

private fun formatMoney(value: Double): String = String.format(Locale.US, "%,.2f", value)
private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)
