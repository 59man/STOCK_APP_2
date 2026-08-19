package com.stocktracker.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.IntentCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stocktracker.core.data.PortfolioRepository
import com.stocktracker.core.data.SettingsRepository
import com.stocktracker.core.designsystem.StockTrackerTheme
import com.stocktracker.feature.portfolio.ConflictRoute
import com.stocktracker.feature.portfolio.ImportRoute
import com.stocktracker.feature.portfolio.PortfolioListRoute
import com.stocktracker.feature.portfolio.PositionDetailRoute
import com.stocktracker.feature.settings.SettingsRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private object Routes {
    const val PORTFOLIO = "portfolio"
    const val SETTINGS = "settings"
    const val IMPORT = "import"
    const val IMPORT_ARG_PORTFOLIO_ID = "portfolioId"
    const val IMPORT_ARG_URI = "uri"
    const val CONFLICTS = "conflicts"
    const val POSITION_DETAIL = "positionDetail"
    const val POSITION_DETAIL_ARG_TICKER = "ticker"
}

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedUriState.value = extractSharedUri(intent)
        setContent {
            val themeMode by settingsRepository.settings.map { it.themeMode }.collectAsState(initial = "system")
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            StockTrackerTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
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

                    NavHost(navController = navController, startDestination = Routes.PORTFOLIO) {
                        composable(Routes.PORTFOLIO) {
                            PortfolioListRoute(
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                                onOpenImport = { portfolioId ->
                                    navController.navigate("${Routes.IMPORT}?${Routes.IMPORT_ARG_PORTFOLIO_ID}=${portfolioId ?: ""}")
                                },
                                onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                                onOpenPositionDetail = { ticker ->
                                    navController.navigate("${Routes.POSITION_DETAIL}/${Uri.encode(ticker)}")
                                },
                            )
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
                        ) { backStackEntry ->
                            val ticker = backStackEntry.arguments?.getString(Routes.POSITION_DETAIL_ARG_TICKER)
                            if (ticker != null) {
                                PositionDetailRoute(ticker = ticker, onBack = { navController.popBackStack() })
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
                        ) { backStackEntry ->
                            val portfolioId = backStackEntry.arguments?.getString(Routes.IMPORT_ARG_PORTFOLIO_ID)?.ifBlank { null }
                            val uriArg = backStackEntry.arguments?.getString(Routes.IMPORT_ARG_URI)?.ifBlank { null }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUriState.value = extractSharedUri(intent)
    }
}
