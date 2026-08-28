package com.stocktracker.core.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.stocktracker.core.data.DivTaxOverrideRepository
import com.stocktracker.core.data.ManualPriceRepository
import com.stocktracker.core.data.PortfolioRepository
import com.stocktracker.core.data.PositionRepository
import com.stocktracker.core.network.PersistKeys
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

enum class SyncTarget { POSITIONS, PORTFOLIOS, MANUAL_PRICES, DIV_TAX_OVERRIDES }

/**
 * Re-serializes and pushes one storage key. Queued with a `NetworkType.CONNECTED`
 * constraint (see SyncCoordinator) so a request made while offline just sits
 * until connectivity returns — WorkManager persists it across app restarts,
 * a real improvement over the web client's fire-and-forget `fetch`.
 */
@HiltWorker
class PushWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val positionRepository: PositionRepository,
    private val portfolioRepository: PortfolioRepository,
    private val manualPriceRepository: ManualPriceRepository,
    private val divTaxOverrideRepository: DivTaxOverrideRepository,
    private val conflictCenter: ConflictCenter,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val portfolioId = inputData.getString(KEY_PORTFOLIO_ID) ?: return Result.failure()
        val target = inputData.getString(KEY_TARGET)?.let { runCatching { SyncTarget.valueOf(it) }.getOrNull() }
            ?: return Result.failure()

        return try {
            when (target) {
                SyncTarget.POSITIONS -> pushOrReport(portfolioId, PersistKeys.positions(portfolioId)) { positionRepository.push(portfolioId) }
                SyncTarget.PORTFOLIOS -> pushOrReport(portfolioId, PersistKeys.PORTFOLIOS) { portfolioRepository.push() }
                SyncTarget.MANUAL_PRICES -> pushOrReport(portfolioId, PersistKeys.manualPrices(portfolioId)) { manualPriceRepository.push(portfolioId) }
                SyncTarget.DIV_TAX_OVERRIDES -> pushOrReport(portfolioId, PersistKeys.divTaxOverrides(portfolioId)) { divTaxOverrideRepository.push(portfolioId) }
            }
        } catch (e: Exception) {
            Log.w("PushWorker", "push failed for $target/$portfolioId, retrying", e)
            Result.retry() // network failure etc. — WorkManager's own backoff handles the wait
        }
    }

    private suspend fun pushOrReport(portfolioId: String, storageKey: String, push: suspend () -> List<MergeConflict<*>>): Result {
        val conflicts = push()
        if (conflicts.isEmpty()) return Result.success()
        conflictCenter.report(portfolioId, storageKey, conflicts)
        return Result.failure() // waits on the user resolving the conflict, not an automatic retry
    }

    companion object {
        const val KEY_PORTFOLIO_ID = "portfolioId"
        const val KEY_TARGET = "target"
    }
}
