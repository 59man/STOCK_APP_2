package com.stocktracker.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.glance.appwidget.updateAll
import com.stocktracker.app.widget.PortfolioWidget
import com.stocktracker.core.data.alerts.PriceAlertRepository
import com.stocktracker.core.data.widget.WidgetSnapshotRepository
import com.stocktracker.core.importer.PdfBoxTextExtractor
import com.stocktracker.feature.portfolio.LogoStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class StockTrackerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var widgetSnapshotRepository: WidgetSnapshotRepository
    @Inject lateinit var priceAlertRepository: PriceAlertRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        PdfBoxTextExtractor.init(this)
        // Bundled logos are copied into app-private storage once, off the main thread: the
        // list reads them as ordinary files, and an asset path cannot be handed to Coil the
        // same way a File can. seedFromAssets never overwrites, so a logo the user set by
        // hand survives every later launch.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            LogoStore(filesDir).seedFromAssets(assets)
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        // Push each new snapshot to any placed widgets; skip the value already on disk at launch.
        scope.launch {
            widgetSnapshotRepository.snapshot.drop(1).collect { PortfolioWidget().updateAll(this@StockTrackerApp) }
        }
        // Keeps the alert job's schedule in step with the table (e.g. after an app update).
        scope.launch { priceAlertRepository.reschedule() }
    }
}
