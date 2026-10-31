package com.stocktracker.core.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stocktracker.core.data.DeviceRegistry
import com.stocktracker.core.data.DivTaxOverrideRepository
import com.stocktracker.core.data.ManualPriceRepository
import com.stocktracker.core.data.PortfolioRepository
import com.stocktracker.core.data.PositionRepository
import com.stocktracker.core.database.SyncStateDao
import com.stocktracker.core.network.PersistKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
    private val deviceRegistry: DeviceRegistry,
    private val syncStateDao: SyncStateDao,
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
     * no-ops if that key is dirty, and independently survives network
     * failures (unreachable server, bad URL, timeout, DNS failure, cleartext
     * blocked, ...) — a pull is opportunistic background refresh, called
     * from app-foreground and "Sync now", and must never crash the caller.
     * Returns the first failure, for a caller that reports one.
     */
    suspend fun pullPortfolio(portfolioId: String): Throwable? = listOfNotNull(
        pullKey(PersistKeys.positions(portfolioId), portfolioId, SyncTarget.POSITIONS) { positionRepository.pull(portfolioId) },
        pullKey(PersistKeys.manualPrices(portfolioId), portfolioId, SyncTarget.MANUAL_PRICES) { manualPriceRepository.pull(portfolioId) },
        pullKey(PersistKeys.divTaxOverrides(portfolioId), portfolioId, SyncTarget.DIV_TAX_OVERRIDES) { divTaxOverrideRepository.pull(portfolioId) },
    ).firstOrNull()

    suspend fun pullPortfolioList(): Throwable? {
        val failure = pullKey(PersistKeys.PORTFOLIOS, GLOBAL_SYNC_SCOPE, SyncTarget.PORTFOLIOS) { portfolioRepository.pull() }
        deviceRegistry.heartbeat() // best-effort, piggybacks on this same cadence — see DeviceRegistry
        return failure
    }

    private suspend fun pullKey(storageKey: String, scope: String, target: SyncTarget, pull: suspend () -> Boolean): Throwable? {
        val failure = runCatching { pull() }.exceptionOrNull()
        failure?.let { logPullFailure(storageKey, it) }
        // Conflicts live in memory and a conflicted push is not retried, so after a restart a dirty
        // key would stay frozen with no prompt. Pushing again re-reports them or finishes the job.
        if (syncStateDao.isDirty(storageKey) == true) enqueuePush(scope, target)
        return failure
    }

    /**
     * Pulls the portfolio list, then every synced key for every portfolio in
     * that (now-current) list — not just whichever one happens to be active.
     * Previously "Sync now" and app-foreground both only ever pulled the
     * active portfolio's positions/manual prices/div-tax overrides, so any
     * other portfolio stayed at whatever was last synced (or empty, on a
     * fresh install) until the user switched to it *and* backgrounded and
     * re-foregrounded the app while it was active. Returns the first failure.
     */
    suspend fun pullAllPortfolios(): Throwable? {
        val listFailure = pullPortfolioList()
        val portfolios = portfolioRepository.observe().first()
        // A portfolio deleted on another device leaves its keys here dirty and never pushed.
        if (listFailure == null && portfolios.isNotEmpty()) {
            val known = portfolios.map { it.id }.toSet()
            syncStateDao.dirtyKeys().filter { it != PersistKeys.PORTFOLIOS }
                .map { it.substringAfterLast('_') }
                .filterNot { it in known }
                .distinct()
                .forEach { syncStateDao.forgetPortfolio(it) }
        }
        val portfolioFailures = portfolios.map { portfolio -> pullPortfolio(portfolio.id) }
        return listFailure ?: portfolioFailures.firstOrNull { it != null }
    }

    /** Local changes the server has not confirmed yet, and why the last attempt failed. */
    val pendingChanges: Flow<Int> get() = syncStateDao.observeDirtyCount()
    val lastPushFailure: StateFlow<String?> get() = conflictCenter.lastPushFailure

    /** Call when the configured server changes: see [SyncStateDao.clearMergeBases]. */
    suspend fun forgetMergeBases() = syncStateDao.clearMergeBases()

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

/** The PORTFOLIOS key has no portfolio of its own; [SyncCoordinator.enqueuePush] only needs a string for the unique work name. */
const val GLOBAL_SYNC_SCOPE = "portfolios"

/** Pulls are best-effort, but a failure should still be diagnosable from logcat. */
private fun logPullFailure(what: String, e: Throwable) {
    android.util.Log.w("Sync", "pull $what failed: ${e.message}")
}
