package com.stocktracker.core.data.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.stocktracker.core.database.PriceAlertDao
import com.stocktracker.core.database.PriceAlertEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import com.stocktracker.core.model.PriceAlert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local price alerts. The periodic check is scheduled only while at least one alert is
 * enabled, so a user who never sets one never has a background job.
 */
@Singleton
class PriceAlertRepository @Inject constructor(
    private val dao: PriceAlertDao,
    @ApplicationContext private val context: Context,
) {
    val alerts: Flow<List<PriceAlert>> = dao.observeAll().map { rows ->
        rows.map { PriceAlert(it.id, it.ticker, it.above, it.threshold, it.currency, it.armed) }
    }

    suspend fun add(ticker: String, above: Boolean, threshold: Double, currency: String) {
        dao.upsert(PriceAlertEntity(UUID.randomUUID().toString(), ticker.uppercase(), above, threshold, currency))
        reschedule()
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        reschedule()
    }

    suspend fun reschedule() {
        val work = WorkManager.getInstance(context)
        if (dao.enabled().isEmpty()) {
            work.cancelUniqueWork(PriceAlertWorker.WORK_NAME)
            return
        }
        // 15 minutes is the shortest period Android allows for periodic work.
        val request = PeriodicWorkRequestBuilder<PriceAlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(PriceAlertWorker.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
