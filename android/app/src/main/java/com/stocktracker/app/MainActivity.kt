package com.stocktracker.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stocktracker.core.data.PortfolioRepository
import com.stocktracker.core.data.SettingsRepository
import com.stocktracker.core.designsystem.StockTrackerTheme
import com.stocktracker.feature.portfolio.ConflictRoute
import com.stocktracker.feature.portfolio.ImportRoute
import com.stocktracker.feature.portfolio.InsightsRoute
import com.stocktracker.feature.portfolio.PortfolioListRoute
import com.stocktracker.feature.portfolio.PortfolioListViewModel
import com.stocktracker.feature.portfolio.PositionDetailRoute
import com.stocktracker.feature.settings.SettingsRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private object Routes {
    const val MAIN_GRAPH = "main"
    const val PORTFOLIO = "portfolio"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"
    const val IMPORT = "import"
    const val IMPORT_ARG_PORTFOLIO_ID = "portfolioId"
    const val IMPORT_ARG_URI = "uri"
    const val CONFLICTS = "conflicts"
    const val POSITION_DETAIL = "positionDetail"
    const val POSITION_DETAIL_ARG_TICKER = "ticker"
}

private data class BottomNavTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val BottomNavTabs = listOf(
    BottomNavTab(Routes.INSIGHTS, "Insights", Icons.Filled.PieChart),
    BottomNavTab(Routes.PORTFOLIO, "Portfolio", Icons.AutoMirrored.Filled.List),
    BottomNavTab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

/** A statement shared into the app (Mail, Files, Drive, …) before the Compose tree exists to consume it. */
private fun extractSharedUri(intent: Intent?): Uri? =
    if (intent?.action == Intent.ACTION_SEND) {
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        null
    }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var portfolioRepository: PortfolioRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private var sharedUriState = mutableStateOf<Uri?>(null)

    // Activity-scoped: read by the splash keep-condition below and shared by every route in setContent.
    private val portfolioViewModel: PortfolioListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hold the system splash until the portfolio is ready (same gate as AppLoadingScreen:
        // lots + first quote/dividend round, 10 s cap) so startup is a single screen instead of
        // splash → spinner screen → content.
        installSplashScreen().setKeepOnScreenCondition { portfolioViewModel.uiState.value.isLoading }
        super.onCreate(savedInstanceState)
        sharedUriState.value = extractSharedUri(intent)
        setContent {
            val themeFlow = remember { settingsRepository.settings.map { it.themeMode } }
            val themeMode by themeFlow.collectAsState(initial = "system")
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            // Activity-scoped (the `portfolioViewModel` property above), not a per-destination
            // lookup: its uiState.isLoading gates the splash + loading screen, and the
            // Portfolio/Insights/PositionDetail routes all share this single instance — a second
            // instance would double every quote/dividend/FX fetch in its init block.
            val portfolioUiState by portfolioViewModel.uiState.collectAsState()

            StockTrackerTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (portfolioUiState.isLoading) {
                        AppLoadingScreen()
                        return@Surface
                    }
                    val navController = rememberNavController()
                    var sharedUri by sharedUriState

                    LaunchedEffect(sharedUri) {
                        val uri = sharedUri ?: return@LaunchedEffect
                        val portfolioId = portfolioRepository.observe().first().firstOrNull()?.id
                        navController.navigate(
                            "${Routes.IMPORT}?${Routes.IMPORT_ARG_PORTFOLIO_ID}=${portfolioId ?: ""}" +
                                "&${Routes.IMPORT_ARG_URI}=${Uri.encode(uri.toString())}",
                        )
                        sharedUri = null
                    }

                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = backStackEntry?.destination?.route

                    Scaffold(
                        // Only shown on the three bottom-nav destinations — a push destination
                        // like Import or PositionDetail hides it, matching the back-stack depth
                        // visually instead of leaving a nav bar that doesn't do anything useful there.
                        bottomBar = {
                            if (BottomNavTabs.any { it.route == currentRoute }) {
                                NavigationBar {
                                    BottomNavTabs.forEach { tab ->
                                        NavigationBarItem(
                                            selected = currentRoute == tab.route,
                                            onClick = {
                                                navController.navigate(tab.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                                            label = { Text(tab.label) },
                                        )
                                    }
                                }
                            }
                        },
                    ) { scaffoldPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = Routes.MAIN_GRAPH,
                            modifier = Modifier.padding(scaffoldPadding),
                        ) {
                            // Portfolio and Insights share the single Activity-scoped portfolioViewModel
                            // hoisted above, so the active-portfolio selection made on one tab is
                            // immediately reflected on the other, instead of each tab independently
                            // defaulting back to the first portfolio.
                            navigation(startDestination = Routes.PORTFOLIO, route = Routes.MAIN_GRAPH) {
                                composable(Routes.PORTFOLIO) {
                                    PortfolioListRoute(
                                        onOpenImport = { portfolioId ->
                                            navController.navigate("${Routes.IMPORT}?${Routes.IMPORT_ARG_PORTFOLIO_ID}=${portfolioId ?: ""}")
                                        },
                                        onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                                        onOpenPositionDetail = { ticker ->
                                            navController.navigate("${Routes.POSITION_DETAIL}/${Uri.encode(ticker)}")
                                        },
                                        viewModel = portfolioViewModel,
                                    )
                                }
                                composable(Routes.INSIGHTS) {
                                    InsightsRoute(viewModel = portfolioViewModel)
                                }
                            }
                            composable(Routes.SETTINGS) {
                                SettingsRoute()
                            }
                            composable(Routes.CONFLICTS) {
                                ConflictRoute(onBack = { navController.popBackStack() })
                            }
                            composable(
                                route = "${Routes.POSITION_DETAIL}/{${Routes.POSITION_DETAIL_ARG_TICKER}}",
                                arguments = listOf(navArgument(Routes.POSITION_DETAIL_ARG_TICKER) { type = NavType.StringType }),
                            ) { backStackEntry2 ->
                                val ticker = backStackEntry2.arguments?.getString(Routes.POSITION_DETAIL_ARG_TICKER)
                                if (ticker != null) {
                                    // Same Activity-scoped instance as PORTFOLIO/INSIGHTS above — a fresh
                                    // default-scoped ViewModel here would re-resolve its own active portfolio
                                    // as portfolios.firstOrNull() (see PortfolioListViewModel.uiState), which
                                    // silently spins forever whenever the ticker lives in any portfolio other
                                    // than the first one in the list.
                                    PositionDetailRoute(ticker = ticker, onBack = { navController.popBackStack() }, viewModel = portfolioViewModel)
                                }
                            }
                            composable(
                                route = "${Routes.IMPORT}?${Routes.IMPORT_ARG_PORTFOLIO_ID}={${Routes.IMPORT_ARG_PORTFOLIO_ID}}" +
                                    "&${Routes.IMPORT_ARG_URI}={${Routes.IMPORT_ARG_URI}}",
                                arguments = listOf(
                                    navArgument(Routes.IMPORT_ARG_PORTFOLIO_ID) {
                                        type = NavType.StringType
                                        nullable = true
                                        defaultValue = null
                                    },
                                    navArgument(Routes.IMPORT_ARG_URI) {
                                        type = NavType.StringType
                                        nullable = true
                                        defaultValue = null
                                    },
                                ),
                            ) { backStackEntry2 ->
                                val portfolioId = backStackEntry2.arguments?.getString(Routes.IMPORT_ARG_PORTFOLIO_ID)?.ifBlank { null }
                                val uriArg = backStackEntry2.arguments?.getString(Routes.IMPORT_ARG_URI)?.ifBlank { null }
                                ImportRoute(
                                    activePortfolioId = portfolioId,
                                    initialUri = uriArg?.let(Uri::parse),
                                    onDone = { navController.popBackStack() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUriState.value = extractSharedUri(intent)
    }
}

/** Shown until portfolioUiState.isLoading flips false — lots loaded + first quote/dividend round settled (10 s cap), see PortfolioListViewModel.startupReady. */
@Composable
private fun AppLoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Stock Tracker", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator()
        }
    }
}
