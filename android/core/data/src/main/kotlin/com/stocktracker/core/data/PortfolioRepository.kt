package com.stocktracker.core.data

import com.stocktracker.core.data.sync.KeyedListSyncEngine
import com.stocktracker.core.data.sync.MergeConflict
import com.stocktracker.core.database.PortfolioDao
import com.stocktracker.core.database.SyncStateDao
import com.stocktracker.core.model.Portfolio
import com.stocktracker.core.network.PersistApi
import com.stocktracker.core.network.PersistKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PortfolioRepository @Inject constructor(
    private val portfolioDao: PortfolioDao,
    persistApi: PersistApi,
    syncStateDao: SyncStateDao,
) {
    private val syncEngine = KeyedListSyncEngine(
        persistApi, syncStateDao, ListSerializer(Portfolio.serializer()), keyOf = { it: Portfolio -> it.id },
    )

    fun observe(): Flow<List<Portfolio>> = portfolioDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun add(name: String): Portfolio {
        val portfolio = Portfolio(id = UUID.randomUUID().toString(), name = name)
        portfolioDao.upsertAll(listOf(portfolio.toEntity()))
        markDirty()
        return portfolio
    }

    suspend fun rename(id: String, name: String) {
        portfolioDao.upsertAll(listOf(Portfolio(id, name).toEntity()))
        markDirty()
    }

    /** Blocking below one remaining portfolio (same guard as usePortfolios.removePortfolio) is the ViewModel's job — it observes the list. */
    suspend fun delete(id: String) {
        portfolioDao.deleteById(id)
        markDirty()
    }

    private suspend fun markDirty() = syncEngine.markDirty(PersistKeys.PORTFOLIOS)

    suspend fun resolveConflict(recordKey: String, keepLocal: Boolean) =
        syncEngine.resolveConflict(
            PersistKeys.PORTFOLIOS, recordKey, keepLocal,
            readLocal = { portfolioDao.getAll().map { it.toDomain() } },
            writeLocal = { merged ->
                portfolioDao.deleteAll()
                portfolioDao.upsertAll(merged.map { it.toEntity() })
            },
        )

    suspend fun pull(): Boolean = syncEngine.pull(PersistKeys.PORTFOLIOS) { remote ->
        portfolioDao.deleteAll()
        portfolioDao.upsertAll(remote.map { it.toEntity() })
    }

    /**
     * Pulls immediately before mutating — same precedent as
     * usePortfolios.mutatePortfolios in the web app, narrowing the window
     * for a same-id conflict on this key down to one network round trip.
     */
    suspend fun push(): List<MergeConflict<Portfolio>> = syncEngine.push(
        PersistKeys.PORTFOLIOS,
        readLocal = { portfolioDao.getAll().map { it.toDomain() } },
        writeLocal = { merged ->
            portfolioDao.deleteAll()
            portfolioDao.upsertAll(merged.map { it.toEntity() })
        },
    )
}
