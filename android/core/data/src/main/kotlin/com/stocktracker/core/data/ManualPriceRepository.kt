package com.stocktracker.core.data

import com.stocktracker.core.data.sync.KeyedMapSyncEngine
import com.stocktracker.core.database.ManualPriceDao
import com.stocktracker.core.database.ManualPriceEntity
import com.stocktracker.core.database.SyncStateDao
import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.network.PersistApi
import com.stocktracker.core.network.PersistKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManualPriceRepository @Inject constructor(
    private val dao: ManualPriceDao,
    persistApi: PersistApi,
    syncStateDao: SyncStateDao,
) {
    // A real modification timestamp exists here (unlike Position/Portfolio), so a
    // same-ticker conflict resolves by recency instead of surfacing a prompt —
    // see the Mobile Sync Blueprint, Phase 3 "Merge".
    private val syncEngine = KeyedMapSyncEngine(
        persistApi, syncStateDao, MapSerializer(String.serializer(), ManualPriceEntry.serializer()),
        resolveConflict = { local, remote -> if (local.updatedAt >= remote.updatedAt) local else remote },
    )

    fun observe(portfolioId: String): Flow<Map<String, ManualPriceEntry>> =
        dao.observeByPortfolio(portfolioId).map { list -> list.associate { it.ticker to it.toDomain() } }

    suspend fun set(portfolioId: String, ticker: String, price: Double, updatedAt: String) {
        dao.upsertAll(listOf(ManualPriceEntity(portfolioId, ticker.uppercase(), price, updatedAt)))
        syncEngine.markDirty(PersistKeys.manualPrices(portfolioId))
    }

    suspend fun clear(portfolioId: String, ticker: String) {
        dao.delete(portfolioId, ticker.uppercase())
        syncEngine.markDirty(PersistKeys.manualPrices(portfolioId))
    }

    suspend fun pull(portfolioId: String): Boolean = syncEngine.pull(PersistKeys.manualPrices(portfolioId)) { remote ->
        dao.deleteByPortfolio(portfolioId)
        dao.upsertAll(remote.map { (ticker, entry) -> ManualPriceEntity(portfolioId, ticker, entry.price, entry.updatedAt) })
    }

    suspend fun push(portfolioId: String) = syncEngine.push(
        PersistKeys.manualPrices(portfolioId),
        readLocal = { dao.getByPortfolio(portfolioId).associate { it.ticker to it.toDomain() } },
        writeLocal = { merged ->
            dao.deleteByPortfolio(portfolioId)
            dao.upsertAll(merged.map { (ticker, entry) -> ManualPriceEntity(portfolioId, ticker, entry.price, entry.updatedAt) })
        },
    )
}
