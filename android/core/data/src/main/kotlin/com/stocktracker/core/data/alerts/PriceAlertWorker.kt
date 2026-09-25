package com.stocktracker.core.data.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stocktracker.core.calc.convert
import com.stocktracker.core.calc.evaluateAlert
import com.stocktracker.core.data.FxRateRepository
import com.stocktracker.core.database.PriceAlertDao
import com.stocktracker.core.network.QuoteClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Locale

/**
 * Checks every enabled alert against a fresh quote and posts one notification per crossing.
 * A quote that fails is skipped this round, not treated as a crossing.
 */
@HiltWorker
class PriceAlertWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: PriceAlertDao,
    private val fxRateRepository: FxRateRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val alerts = dao.enabled()
        if (alerts.isEmpty()) return Result.success()
        runCatching { fxRateRepository.refresh() }
        val rates = fxRateRepository.rates.value

        alerts.groupBy { it.ticker }.forEach { (ticker, tickerAlerts) ->
            val quote = runCatching { QuoteClient.fetchQuote(ticker) }
                .onFailure { Log.w(TAG, "quote for $ticker failed, skipping this round: ${it.message}") }
                .getOrNull() ?: return@forEach
            tickerAlerts.forEach { alert ->
                val price = convert(quote.price, quote.currency, alert.currency, rates)
                val decision = evaluateAlert(alert.above, alert.threshold, alert.armed, price)
                if (decision.notify) notify(alert.id, ticker, alert.above, alert.threshold, price, alert.currency)
                if (decision.armed != alert.armed || decision.notify) {
                    dao.upsert(
                        alert.copy(
                            armed = decision.armed,
                            lastTriggeredAt = if (decision.notify) System.currentTimeMillis() else alert.lastTriggeredAt,
                        ),
                    )
                }
            }
        }
        return Result.success()
    }

    private fun notify(id: String, ticker: String, above: Boolean, threshold: Double, price: Double, currency: String) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "$ticker crossed its alert but notifications are not permitted")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Price alerts", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val fmt = { v: Double -> String.format(Locale.US, "%,.2f %s", v, currency) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$ticker ${if (above) "above" else "below"} ${fmt(threshold)}")
            .setContentText("Now ${fmt(price)}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }

    companion object {
        const val WORK_NAME = "price-alerts"
        private const val CHANNEL_ID = "price_alerts"
        private const val TAG = "PriceAlerts"
    }
}
