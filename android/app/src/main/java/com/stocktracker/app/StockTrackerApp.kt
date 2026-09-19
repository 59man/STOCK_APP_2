package com.stocktracker.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.stocktracker.core.importer.PdfBoxTextExtractor
import com.stocktracker.feature.portfolio.LogoStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class StockTrackerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

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
    }
}
