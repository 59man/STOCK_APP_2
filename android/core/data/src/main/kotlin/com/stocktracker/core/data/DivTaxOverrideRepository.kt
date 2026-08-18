package com.stocktracker.core.data

import com.stocktracker.core.data.sync.KeyedMapSyncEngine
import com.stocktracker.core.database.DivTaxOverrideDao
import com.stocktracker.core.database.DivTaxOverrideEntity
import com.stocktracker.core.database.SyncStateDao
import com.stocktracker.core.network.PersistApi
import com.stocktracker.core.network.PersistKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DivTaxOverrideRepository @Inject constructor(
    private val dao: DivTaxOverrideDao,
    persistApi: PersistApi,
    syncStateDao: SyncStateDao,
) {
    // No updatedAt on this key (unlike manual prices) — a same-key conflict has no
    // principled recency to resolve by, so it surfaces to the user like Position/Portfolio.
    private val syncEngine = KeyedMapSyncEngine(
        persistApi, syncStateDao, MapSerializer(String.serializer(), Double.serializer()),
    )

    /** Map key mirrors the web app's `"TICKER::YYYY-MM-DD"` composite key. */
    private fun key(ticker: String, date: String) = "${ticker.uppercase()}::$date"

    fun observe(portfolioId: String): Flow<Map<String, Double>> =
        dao.observeByPortfolio(portfolioId).map { list -> list.associate { key(it.ticker, it.date) to it.rate } }

    suspend fun set(portfolioId: String, ticker: String, date: String, rate: Double) {
        dao.upsertAll(listOf(DivTaxOverrideEntity(portfolioId, ticker.uppercase(), date, rate)))
        syncEngine.markDirty(PersistKeys.divTaxOverrides(portfolioId))
    }

    suspend fun clear(portfolioId: String, ticker: String, date: String) {
        dao.delete(portfolioId, ticker.uppercase(), date)
        syncEngine.markDirty(PersistKeys.divTaxOverrides(portfolioId))
    }

    private fun toEntities(portfolioId: String, map: Map<String, Double>): List<DivTaxOverrideEntity> =
        map.mapNotNull { (compositeKey, rate) ->
            val parts = compositeKey.split("::")
            if (parts.size != 2) return@mapNotNull null
            DivTaxOverrideEntity(portfolioId, parts[0], parts[1], rate)
        }

    suspend fun resolveConflict(portfolioId: String, recordKey: String, keepLocal: Boolean) =
        syncEngine.resolveConflict(
            PersistKeys.divTaxOverrides(portfolioId), recordKey, keepLocal,
            readLocal = { dao.getByPortfolio(portfolioId).associate { key(it.ticker, it.date) to it.rate } },
            writeLocal = { merged ->
                dao.deleteByPortfolio(portfolioId)
                dao.upsertAll(toEntities(portfolioId, merged))
            },
        )

    suspend fun pull(portfolioId: String): Boolean = syncEngine.pull(PersistKeys.divTaxOverrides(portfolioId)) { remote ->
        dao.deleteByPortfolio(portfolioId)
        dao.upsertAll(toEntities(portfolioId, remote))
    }

    suspend fun push(portfolioId: String) = syncEngine.push(
        PersistKeys.divTaxOverrides(portfolioId),
        readLocal = { dao.getByPortfolio(portfolioId).associate { key(it.ticker, it.date) to it.rate } },
        writeLocal = { merged ->
            dao.deleteByPortfolio(portfolioId)
            dao.upsertAll(toEntities(portfolioId, merged))
        },
    )
}
