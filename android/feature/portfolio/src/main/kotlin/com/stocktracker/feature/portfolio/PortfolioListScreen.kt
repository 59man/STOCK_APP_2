package com.stocktracker.feature.portfolio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.stocktracker.core.designsystem.components.AppDialog
import com.stocktracker.core.designsystem.components.Badge
import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.PositionType
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

@Composable
fun PortfolioListRoute(
    onOpenImport: (String?) -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenPositionDetail: (String) -> Unit,
    viewModel: PortfolioListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

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
        onOpenImport = onOpenImport,
        onOpenConflicts = onOpenConflicts,
        onOpenPositionDetail = onOpenPositionDetail,
        onExport = { exportLauncher.launch("portfolio_${LocalDate.now()}.json") },
        onAddPosition = { showAddDialog = true },
    )

    if (showAddDialog && uiState.activePortfolioId != null) {
        AddPositionDialog(
            portfolioId = uiState.activePortfolioId!!,
            onDismiss = { showAddDialog = false },
            onAdded = { showAddDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortfolioListScreen(
    uiState: PortfolioListUiState,
    onAction: (PortfolioListAction) -> Unit,
    onOpenImport: (String?) -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenPositionDetail: (String) -> Unit,
    onExport: () -> Unit,
    onAddPosition: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stock Tracker") },
                actions = {
                    IconButton(onClick = { onOpenImport(uiState.activePortfolioId) }) {
                        Icon(ImportIcon, contentDescription = "Import statement")
                    }
                    IconButton(onClick = onExport, enabled = uiState.activePortfolioId != null) {
                        Icon(ExportIcon, contentDescription = "Export portfolio")
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
                    color = StockTrackerColors.loss.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenConflicts() },
                ) {
                    Text(
                        "⚠ ${uiState.conflictCount} sync conflict${if (uiState.conflictCount == 1) "" else "s"} — tap to review",
                        color = StockTrackerColors.loss,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            PortfolioTabs(uiState, onAction)
            CurrencyTabs(uiState, onAction)

            if (uiState.closedCount > 0) {
                TextButton(onClick = { onAction(PortfolioListAction.ToggleShowClosed) }) {
                    Text(if (uiState.showClosed) "Hide closed" else "Show closed (${uiState.closedCount})")
                }
            }

            if (uiState.isLoading || uiState.isSwitchingPortfolio) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (uiState.rows.isEmpty()) {
                EmptyPortfolioState(
                    onImportStatement = { onOpenImport(uiState.activePortfolioId) },
                    onAddManually = onAddPosition,
                )
            } else {
                LazyColumn(
                    // Extra bottom inset so the floating "+" button never overlaps the last
                    // card's content.
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.visibleRows, key = { it.ticker }) { row ->
                        PositionCard(
                            row = row,
                            displayCurrency = uiState.displayCurrency,
                            rates = uiState.rates,
                            onOpenDetail = { onOpenPositionDetail(row.ticker) },
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
internal fun PortfolioTabs(uiState: PortfolioListUiState, onAction: (PortfolioListAction) -> Unit) {
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
        AppDialog(
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
        AppDialog(
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
        AppDialog(
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

private val DISPLAY_CURRENCIES = listOf("CZK", "USD", "EUR")

/** Quick display-currency switcher — mirrors the web app's .currency-tabs, but persists to SettingsRepository instead of resetting per session. */
@Composable
internal fun CurrencyTabs(uiState: PortfolioListUiState, onAction: (PortfolioListAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DISPLAY_CURRENCIES.forEach { currency ->
            val active = currency == uiState.displayCurrency
            // Same selected/unselected language as PortfolioTabs above: accent fill when active,
            // quiet neutral otherwise. The old per-currency colours made the *selected* CZK tab
            // (grey badge colour) look disabled next to the brightly tinted unselected ones.
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                border = if (active) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.padding(2.dp),
            ) {
                Text(
                    text = currency,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { onAction(PortfolioListAction.SetDisplayCurrency(currency)) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AddPortfolioDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    com.stocktracker.core.designsystem.components.AppDialog(
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
internal fun SummaryHeader(rows: List<PortfolioRow>, displayCurrency: String, rates: Map<String, Double>, portfolioIrr: Double?) {
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
                    "${if (totalReturn >= 0) "▲" else "▼"} ${signedMoney(totalReturn)} (${signedPercent(returnPercent)})",
                    style = NumericTypography.bodyMedium,
                    color = pnlColor(totalReturn),
                )
            }
        }
        val pnlPercent = if (totalCostBasis > 0) totalPnl / totalCostBasis * 100 else null
        DetailMetricGrid(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            cells = listOf(
                DetailMetric(
                    "Today's change", signedMoney(totalDailyChange),
                    sub = signedPercent(dailyChangePercent), color = pnlColorRounded(dailyChangePercent),
                ),
                DetailMetric(
                    "Total return", signedMoney(totalReturn),
                    sub = signedPercent(returnPercent), color = pnlColor(totalReturn),
                ),
                DetailMetric(
                    "Price P&L", signedMoney(totalPnl),
                    sub = pnlPercent?.let(::signedPercent), color = pnlColor(totalPnl),
                ),
                DetailMetric(
                    "Net dividends",
                    if (totalDividends > 0) signedMoney(totalDividends) else "—",
                    color = if (totalDividends > 0) StockTrackerColors.gain else null,
                ),
                DetailMetric("Cost basis", formatMoney(totalCostBasis), muted = true),
                DetailMetric(
                    "IRR p.a.",
                    if (portfolioIrr != null) signedPercent(portfolioIrr * 100) else "…",
                    color = portfolioIrr?.let { pnlColor(it) },
                ),
            ),
        )
    }
}

@Composable
internal fun PositionCard(
    row: PortfolioRow,
    displayCurrency: String,
    rates: Map<String, Double>,
    onOpenDetail: () -> Unit,
) {
    fun dc(amount: Double) = com.stocktracker.core.calc.convert(amount, row.currency, displayCurrency, rates)
    val dailyPct = dailyChangePercent(row)

    AppCard(modifier = Modifier.fillMaxWidth()) {
        // XTB-style compact row: logo, ticker/name, and only today's %, total return incl.
        // dividends, and current price. Everything else (avg buy, IRR, buy/sell/delete) lives on
        // PositionDetailRoute now, reached by tapping the row.
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDetail),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                PositionLogo(row.ticker, row.type)
                Column(modifier = Modifier.padding(start = Spacing.sm)) {
                    // Ticker symbol is deliberately not shown here — it's the detail screen's
                    // TopAppBar title (PositionDetailRoute). The card leads with the full name;
                    // type/currency/SOLD badges sit where the name used to (below it).
                    Text(
                        row.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
                        Badge(
                            typeBadgeLabel(row.type),
                            containerColor = typeBadgeColor(row.type),
                            contentColor = Color.White,
                        )
                        Badge(
                            row.nativeCurrency,
                            modifier = Modifier.padding(start = Spacing.sm),
                            containerColor = currencyBadgeColor(row.nativeCurrency),
                            contentColor = Color.White,
                        )
                        if (row.isClosed) {
                            Badge(
                                "SOLD",
                                modifier = Modifier.padding(start = Spacing.sm),
                                containerColor = Color.Gray,
                                contentColor = Color.White,
                            )
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatMoney(dc(row.currentValue)),
                    style = NumericTypography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(modifier = Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Badge(
                        signedPercent(dailyPct),
                        containerColor = pnlColorRounded(dailyPct).copy(alpha = 0.16f),
                        contentColor = pnlColorRounded(dailyPct),
                        emphasized = true,
                    )
                    Badge(
                        signedMoney(dc(row.totalReturn)),
                        containerColor = pnlColor(row.totalReturn).copy(alpha = 0.16f),
                        contentColor = pnlColor(row.totalReturn),
                        emphasized = true,
                    )
                }
            }
        }
    }
}

/** `dailyChange` on [PortfolioRow] is absolute money only — derive today's % the same way SummaryHeader does at portfolio level. */
private fun dailyChangePercent(row: PortfolioRow): Double {
    val prevValue = row.currentValue - row.dailyChange
    return if (prevValue > 0) (row.dailyChange / prevValue) * 100 else 0.0
}

private fun typeBadgeLabel(type: PositionType): String = when (type) {
    PositionType.STOCK -> "Stock"
    PositionType.ETF -> "ETF"
    PositionType.FUND -> "Fund"
    PositionType.COMMODITY -> "Commodity"
    PositionType.CRYPTO -> "Crypto"
}

private fun typeBadgeColor(type: PositionType): Color = when (type) {
    PositionType.STOCK -> Color(0xFF3B82F6)
    PositionType.ETF -> Color(0xFF22C55E)
    PositionType.FUND -> Color(0xFFA855F7)
    PositionType.COMMODITY -> Color(0xFFEAB308)
    PositionType.CRYPTO -> Color(0xFFFF9F1C)
}

/** Fixed color per currency the app's FX rates cover (CZK base + the 7 useFxRates pairs), so the same currency always reads as the same color across the app. */
private fun currencyBadgeColor(currency: String): Color = when (currency.uppercase()) {
    "CZK" -> Color(0xFF64748B)
    "USD" -> Color(0xFF16A34A)
    "EUR" -> Color(0xFF2563EB)
    "GBP" -> Color(0xFFDB2777)
    "CHF" -> Color(0xFFDC2626)
    "JPY" -> Color(0xFFEA580C)
    "CAD" -> Color(0xFF0D9488)
    "AUD" -> Color(0xFF7C3AED)
    else -> Color(0xFF6B7280)
}

/**
 * Company domains for tickers FMP's image-stock endpoint doesn't cover (mostly non-US listings),
 * used as a second logo source via Clearbit's free no-key domain-based logo API. Same
 * one-line-per-ticker curated-map pattern as TICKER_COUNTRY (core/calc/Dividends.kt). XAU/4GLD.DE
 * are deliberately absent — a commodity ETC has no company logo to fetch, so it should fall
 * straight through to the initials avatar.
 */
private val TICKER_LOGO_DOMAINS: Map<String, String> = mapOf(
    "VIG.PR" to "vig.com",
    "UCG.MI" to "unicreditgroup.eu",
    "DTE.DE" to "telekom.com",
    "8306.T" to "mufg.jp",
    "8591.T" to "orix.co.jp",
    "CSG.AS" to "csgroup.cz",
    "CSG.PR" to "csgroup.cz",
    "COLT.PR" to "coltcz.com",
    "CZG.PR" to "coltcz.com",
    "FIOG.PR" to "fio.cz",
    "LU2606422355" to "onemarkets.cz",
    "LU2606421548" to "onemarkets.cz",
    "LU2595011649" to "onemarkets.cz",
    "EXUS.DE" to "ishares.com",
)

/** 40dp circular avatar: FMP logo, falling back to a Clearbit domain logo (if known), falling back to a colored initial letter. */
@Composable
private fun PositionLogo(ticker: String, type: PositionType) {
    val baseSymbol = ticker.substringBefore(".")
    val clearbitDomain = TICKER_LOGO_DOMAINS[ticker.uppercase()]
    SubcomposeAsyncImage(
        model = "https://financialmodelingprep.com/image-stock/$baseSymbol.png",
        contentDescription = null,
        modifier = Modifier.size(40.dp).clip(CircleShape),
        loading = { InitialAvatar(ticker, type) },
        error = {
            if (clearbitDomain != null) {
                SubcomposeAsyncImage(
                    model = "https://logo.clearbit.com/$clearbitDomain",
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                    loading = { InitialAvatar(ticker, type) },
                    error = { InitialAvatar(ticker, type) },
                )
            } else {
                InitialAvatar(ticker, type)
            }
        },
    )
}

@Composable
private fun InitialAvatar(ticker: String, type: PositionType) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(typeBadgeColor(type)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ticker.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
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
    var sellTarget by remember { mutableStateOf(false) }
    var manualPriceTarget by remember { mutableStateOf(false) }

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
                run {
                    fun dc(amount: Double) = com.stocktracker.core.calc.convert(amount, row.currency, uiState.displayCurrency, uiState.rates)
                    fun signed(v: Double) = signedMoney(v)
                    fun signedPct(v: Double) = signedPercent(v)
                    val dailyPct = dailyChangePercent(row)
                    val totalReturnPct = if (row.costBasis > 0) row.totalReturn / row.costBasis * 100 else null
                    val cur = uiState.displayCurrency
                    DetailMetricGrid(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
                        cells = listOf(
                            DetailMetric("Current value", "${formatMoney(dc(row.currentValue))} $cur"),
                            DetailMetric("Cost basis", "${formatMoney(dc(row.costBasis))} $cur", muted = true),
                            DetailMetric("Current price", "${formatMoney(dc(row.currentPrice))} $cur"),
                            DetailMetric(
                                "Today's change", signed(dc(row.dailyChange)),
                                sub = signedPct(dailyPct), color = pnlColorRounded(dailyPct),
                            ),
                            DetailMetric(
                                "Price P&L", signed(dc(row.pnl)),
                                sub = signedPct(row.pnlPercent), color = pnlColor(row.pnl),
                            ),
                            DetailMetric(
                                "Net dividends",
                                if (row.dividendIncome > 0) signed(dc(row.dividendIncome)) else "—",
                                color = if (row.dividendIncome > 0) StockTrackerColors.gain else null,
                            ),
                            DetailMetric(
                                "Total return", signed(dc(row.totalReturn)),
                                sub = totalReturnPct?.let(::signedPct), color = pnlColor(row.totalReturn),
                            ),
                            DetailMetric(
                                "IRR p.a.", row.irr?.let { formatPercent(it * 100) } ?: "—",
                                color = row.irr?.let { pnlColor(it) },
                            ),
                        ),
                    )
                }
                if (!row.isClosed) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.End) {
                        AppButton(
                            text = if (row.priceIsManual) "M ${'$'}" else "Set price",
                            onClick = { manualPriceTarget = true },
                            variant = if (row.priceIsManual) AppButtonVariant.Primary else AppButtonVariant.Secondary,
                            emphasized = row.priceIsManual,
                        )
                        AppButton(text = "Sell", onClick = { sellTarget = true }, variant = AppButtonVariant.Secondary)
                        AppButton(
                            text = "Delete",
                            onClick = { row.ids.forEach { id -> viewModel.onAction(PortfolioListAction.DeletePosition(id)) }; onBack() },
                            variant = AppButtonVariant.Danger,
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    AppButton(text = "✎ Edit ticker/name/ISIN", onClick = { editTickerTarget = true })
                }
                if (row.positions.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = Spacing.md)) {
                        LotListSection(positions = row.positions, onEdit = { lot -> editTarget = lot })
                    }
                }
                DividendSection(
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

            if (manualPriceTarget && uiState.activePortfolioId != null) {
                ManualPriceDialog(
                    portfolioId = uiState.activePortfolioId!!,
                    ticker = row.ticker,
                    quantity = row.totalQuantity,
                    currentManual = if (row.priceIsManual) {
                        com.stocktracker.core.model.ManualPriceEntry(row.currentPrice, row.manualPriceDate ?: "")
                    } else {
                        null
                    },
                    onDismiss = { manualPriceTarget = false },
                    onDone = { manualPriceTarget = false },
                )
            }
            if (sellTarget) {
                SellPositionDialog(
                    row = row,
                    onDismiss = { sellTarget = false },
                    onConfirm = { sellPrice, sellDate ->
                        viewModel.onAction(PortfolioListAction.SellPositions(row.ids, sellPrice, sellDate))
                        sellTarget = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun DivTaxEditDialog(
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

    AppDialog(
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
internal fun pnlColor(value: Double): Color = if (value < 0) StockTrackerColors.loss else StockTrackerColors.gain
@Composable
internal fun pnlColor(formatted: String): Color = if (formatted.startsWith("-")) StockTrackerColors.loss else StockTrackerColors.gain

internal fun formatMoney(value: Double): String = String.format(Locale.US, "%,.2f", value)
internal fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

/** Gain/loss money with an explicit sign: "+1,234.56" / "-1,234.56" / "0.00" — never "-0.00". */
internal fun signedMoney(value: Double): String {
    val v = Math.round(value * 100) / 100.0
    return when {
        v > 0 -> "+" + formatMoney(v)
        v < 0 -> formatMoney(v)
        else -> formatMoney(0.0)
    }
}

/** Gain/loss percent with an explicit sign; anything that rounds to 0.0 prints "0.0%" (no "-0.0%"). */
internal fun signedPercent(value: Double): String {
    val v = Math.round(value * 10) / 10.0
    return when {
        v > 0 -> "+" + formatPercent(v)
        v < 0 -> formatPercent(v)
        else -> formatPercent(0.0)
    }
}

/** Colour for a value as it will be *displayed* — a -0.004 that prints as "0.0%" is neutral, not red. */
@Composable
internal fun pnlColorRounded(value: Double, decimals: Int = 1): Color {
    val factor = Math.pow(10.0, decimals.toDouble())
    val v = Math.round(value * factor) / factor
    return if (v < 0) StockTrackerColors.loss else if (v > 0) StockTrackerColors.gain else MaterialTheme.colorScheme.onSurfaceVariant
}

private val DisplayDateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

/** "2022-05-23" → "23 May 2022"; anything unparseable is returned unchanged. */
internal fun formatDisplayDate(isoDate: String): String =
    runCatching { java.time.LocalDate.parse(isoDate.take(10)).format(DisplayDateFormat) }.getOrDefault(isoDate)

internal data class DetailMetric(
    val label: String,
    val value: String,
    val sub: String? = null,
    val color: Color? = null,
    val muted: Boolean = false,
)

/**
 * Two-column bordered grid of metric boxes — the phone-sized twin of the web app's
 * `.summary-grid` / `.summary-card` block: uppercase letter-spaced label, bold value,
 * optional percent sub-line, hairline dividers between cells.
 */
@Composable
internal fun DetailMetricGrid(cells: List<DetailMetric>, modifier: Modifier = Modifier) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        cells.chunked(2).forEachIndexed { rowIndex, pair ->
            if (rowIndex > 0) HorizontalDivider(color = borderColor)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                pair.forEachIndexed { i, cell ->
                    if (i > 0) VerticalDivider(color = borderColor)
                    DetailMetricCell(cell, Modifier.weight(1f).fillMaxHeight())
                }
                if (pair.size == 1) {
                    VerticalDivider(color = borderColor)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DetailMetricCell(cell: DetailMetric, modifier: Modifier) {
    Column(modifier.padding(horizontal = Spacing.md, vertical = Spacing.md)) {
        Text(
            cell.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            cell.value,
            style = NumericTypography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = cell.color ?: if (cell.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.xs),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        cell.sub?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = cell.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// Material "file_download" / "file_upload" glyphs, built inline so the module doesn't need the
// multi-megabyte material-icons-extended artifact for two icons.
private val ImportIcon = materialIcon(name = "StockTracker.Import") {
    materialPath {
        moveTo(19f, 9f); horizontalLineToRelative(-4f); verticalLineTo(3f); horizontalLineTo(9f)
        verticalLineToRelative(6f); horizontalLineTo(5f); lineToRelative(7f, 7f); lineToRelative(7f, -7f); close()
        moveTo(5f, 18f); verticalLineToRelative(2f); horizontalLineToRelative(14f); verticalLineToRelative(-2f); horizontalLineTo(5f); close()
    }
}

private val ExportIcon = materialIcon(name = "StockTracker.Export") {
    materialPath {
        moveTo(9f, 16f); horizontalLineToRelative(6f); verticalLineToRelative(-6f); horizontalLineToRelative(4f)
        lineToRelative(-7f, -7f); lineToRelative(-7f, 7f); horizontalLineToRelative(4f); close()
        moveTo(5f, 18f); horizontalLineToRelative(14f); verticalLineToRelative(2f); horizontalLineTo(5f); close()
    }
}
