package com.stocktracker.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PortfolioEntity::class,
        PositionEntity::class,
        ManualPriceEntity::class,
        DivTaxOverrideEntity::class,
        SyncStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class StockTrackerDatabase : RoomDatabase() {
    abstract fun portfolioDao(): PortfolioDao
    abstract fun positionDao(): PositionDao
    abstract fun manualPriceDao(): ManualPriceDao
    abstract fun divTaxOverrideDao(): DivTaxOverrideDao
    abstract fun syncStateDao(): SyncStateDao
}
