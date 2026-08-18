package com.stocktracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stocktracker.core.designsystem.StockTrackerTheme
import com.stocktracker.feature.portfolio.ConflictRoute
import com.stocktracker.feature.portfolio.ImportRoute
import com.stocktracker.feature.portfolio.PortfolioListRoute
import com.stocktracker.feature.settings.SettingsRoute
import dagger.hilt.android.AndroidEntryPoint

private object Routes {
    const val PORTFOLIO = "portfolio"
    const val SETTINGS = "settings"
    const val IMPORT = "import"
    const val IMPORT_ARG_PORTFOLIO_ID = "portfolioId"
    const val CONFLICTS = "conflicts"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StockTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = Routes.PORTFOLIO) {
                        composable(Routes.PORTFOLIO) {
                            PortfolioListRoute(
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                                onOpenImport = { portfolioId ->
                                    navController.navigate("${Routes.IMPORT}?${Routes.IMPORT_ARG_PORTFOLIO_ID}=${portfolioId ?: ""}")
                                },
                                onOpenConflicts = { navController.navigate(Routes.CONFLICTS) },
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsRoute()
                        }
                        composable(Routes.CONFLICTS) {
                            ConflictRoute(onBack = { navController.popBackStack() })
                        }
                        composable(
                            route = "${Routes.IMPORT}?${Routes.IMPORT_ARG_PORTFOLIO_ID}={${Routes.IMPORT_ARG_PORTFOLIO_ID}}",
                            arguments = listOf(
                                navArgument(Routes.IMPORT_ARG_PORTFOLIO_ID) {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                            ),
                        ) { backStackEntry ->
                            val portfolioId = backStackEntry.arguments?.getString(Routes.IMPORT_ARG_PORTFOLIO_ID)?.ifBlank { null }
                            ImportRoute(
                                activePortfolioId = portfolioId,
                                onDone = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
