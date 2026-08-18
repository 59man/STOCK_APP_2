package com.stocktracker.core.data

import com.stocktracker.core.data.sync.KeyedListSyncEngine
import com.stocktracker.core.data.sync.MergeConflict
import com.stocktracker.core.database.PositionDao
import com.stocktracker.core.database.SyncStateDao
import com.stocktracker.core.model.Position
import com.stocktracker.core.network.PersistApi
import com.stocktracker.core.network.PersistKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PositionRepository @Inject constructor(
    private val positionDao: PositionDao,
    persistApi: PersistApi,
    syncStateDao: SyncStateDao,
) {
    private val syncEngine = KeyedListSyncEngine(
        persistApi, syncStateDao, ListSerializer(Position.serializer()), keyOf = { it: Position -> it.id },
    )

    fun observe(portfolioId: String): Flow<List<Position>> =
        positionDao.observeByPortfolio(portfolioId).map { list -> list.map { it.toDomain() } }

    suspend fun upsert(portfolioId: String, position: Position) {
        positionDao.upsertAll(listOf(position.toEntity(portfolioId)))
        syncEngine.markDirty(PersistKeys.positions(portfolioId))
    }

    suspend fun upsertAll(portfolioId: String, positions: List<Position>) {
        positionDao.upsertAll(positions.map { it.toEntity(portfolioId) })
        syncEngine.markDirty(PersistKeys.positions(portfolioId))
    }

    suspend fun delete(portfolioId: String, positionId: String) {
        positionDao.deleteById(positionId)
        syncEngine.markDirty(PersistKeys.positions(portfolioId))
    }

    /** Sell one or more open lots — the app's existing "stamp sellPrice/sellDate" semantics. */
    suspend fun sell(portfolioId: String, positionIds: List<String>, sellPrice: Double, sellDate: String) {
        val current = positionDao.getByPortfolio(portfolioId).associateBy { it.id }
        val updated = positionIds.mapNotNull { id -> current[id]?.copy(sellPrice = sellPrice, sellDate = sellDate) }
        positionDao.upsertAll(updated)
        syncEngine.markDirty(PersistKeys.positions(portfolioId))
    }

    suspend fun pull(portfolioId: String): Boolean =
        syncEngine.pull(PersistKeys.positions(portfolioId)) { remote ->
            positionDao.deleteByPortfolio(portfolioId)
            positionDao.upsertAll(remote.map { it.toEntity(portfolioId) })
        }

    suspend fun resolveConflict(portfolioId: String, recordKey: String, keepLocal: Boolean) =
        syncEngine.resolveConflict(
            PersistKeys.positions(portfolioId), recordKey, keepLocal,
            readLocal = { positionDao.getByPortfolio(portfolioId).map { it.toDomain() } },
            writeLocal = { merged ->
                positionDao.deleteByPortfolio(portfolioId)
                positionDao.upsertAll(merged.map { it.toEntity(portfolioId) })
            },
        )

    suspend fun push(portfolioId: String): List<MergeConflict<Position>> =
        syncEngine.push(
            PersistKeys.positions(portfolioId),
            readLocal = { positionDao.getByPortfolio(portfolioId).map { it.toDomain() } },
            writeLocal = { merged ->
                positionDao.deleteByPortfolio(portfolioId)
                positionDao.upsertAll(merged.map { it.toEntity(portfolioId) })
            },
        )
}
