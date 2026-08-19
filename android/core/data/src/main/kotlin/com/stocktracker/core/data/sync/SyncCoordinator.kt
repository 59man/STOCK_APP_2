package com.stocktracker.core.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stocktracker.core.data.DivTaxOverrideRepository
import com.stocktracker.core.data.ManualPriceRepository
import com.stocktracker.core.data.PortfolioRepository
import com.stocktracker.core.data.PositionRepository
import com.stocktracker.core.network.PersistKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point for the sync engine's push/pull triggers — see the Mobile
 * Sync Blueprint, Phase 3. Call [enqueuePush] right after any local
 * mutation (the repositories already mark the affected key dirty; this is
 * what actually schedules the network attempt). Call [pullPortfolio] on app
 * foreground, manual refresh, or a periodic tick.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val positionRepository: PositionRepository,
    private val portfolioRepository: PortfolioRepository,
    private val manualPriceRepository: ManualPriceRepository,
    private val divTaxOverrideRepository: DivTaxOverrideRepository,
    private val conflictCenter: ConflictCenter,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun enqueuePush(portfolioId: String, target: SyncTarget) {
        val uniqueName = "push-${target.name}-$portfolioId"
        val request = OneTimeWorkRequestBuilder<PushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(2, TimeUnit.SECONDS) // coalesces rapid successive edits into one push
            .setInputData(workDataOf(PushWorker.KEY_PORTFOLIO_ID to portfolioId, PushWorker.KEY_TARGET to target.name))
            .build()
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Pulls every synced key for one portfolio. Each pull independently
     * no-ops if that key is dirty, and independently swallows network
     * failures (unreachable server, bad URL, timeout, DNS failure, cleartext
     * blocked, ...) — a pull is opportunistic background refresh, called
     * from app-foreground and "Sync now", and must never crash the caller.
     */
    suspend fun pullPortfolio(portfolioId: String) {
        runCatching { positionRepository.pull(portfolioId) }
        runCatching { manualPriceRepository.pull(portfolioId) }
        runCatching { divTaxOverrideRepository.pull(portfolioId) }
    }

    suspend fun pullPortfolioList() {
        runCatching { portfolioRepository.pull() }
    }

    /**
     * Pulls the portfolio list, then every synced key for every portfolio in
     * that (now-current) list — not just whichever one happens to be active.
     * Previously "Sync now" and app-foreground both only ever pulled the
     * active portfolio's positions/manual prices/div-tax overrides, so any
     * other portfolio stayed at whatever was last synced (or empty, on a
     * fresh install) until the user switched to it *and* backgrounded and
     * re-foregrounded the app while it was active.
     */
    suspend fun pullAllPortfolios() {
        pullPortfolioList()
        portfolioRepository.observe().first().forEach { portfolio -> pullPortfolio(portfolio.id) }
    }

    /**
     * Applies the user's "keep mine" / "keep server's" choice for one
     * reported conflict, then re-enqueues that key's push — reconciled per
     * [KeyedListSyncEngine.resolveConflict], it now succeeds cleanly.
     */
    suspend fun resolveConflict(conflict: PendingConflict, keepLocal: Boolean) {
        when {
            conflict.storageKey == PersistKeys.PORTFOLIOS -> {
                portfolioRepository.resolveConflict(conflict.recordKey, keepLocal)
                enqueuePush(conflict.portfolioId, SyncTarget.PORTFOLIOS)
            }
            conflict.storageKey == PersistKeys.positions(conflict.portfolioId) -> {
                positionRepository.resolveConflict(conflict.portfolioId, conflict.recordKey, keepLocal)
                enqueuePush(conflict.portfolioId, SyncTarget.POSITIONS)
            }
            conflict.storageKey == PersistKeys.divTaxOverrides(conflict.portfolioId) -> {
                divTaxOverrideRepository.resolveConflict(conflict.portfolioId, conflict.recordKey, keepLocal)
                enqueuePush(conflict.portfolioId, SyncTarget.DIV_TAX_OVERRIDES)
            }
        }
        conflictCenter.clear(conflict.storageKey, conflict.recordKey)
    }
}
