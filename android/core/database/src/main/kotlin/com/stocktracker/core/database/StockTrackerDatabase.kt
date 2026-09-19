package com.stocktracker.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PortfolioEntity::class,
        PositionEntity::class,
        ManualPriceEntity::class,
        DivTaxOverrideEntity::class,
        SyncStateEntity::class,
        InstrumentProfileEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class StockTrackerDatabase : RoomDatabase() {
    abstract fun portfolioDao(): PortfolioDao
    abstract fun positionDao(): PositionDao
    abstract fun manualPriceDao(): ManualPriceDao
    abstract fun divTaxOverrideDao(): DivTaxOverrideDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun instrumentProfileDao(): InstrumentProfileDao
}

/**
 * Adds the instrument profile cache. Creates one table and touches nothing else — the database
 * holds positions and unresolved sync conflicts, so destructive fallback is deliberately not
 * enabled and this must never drop or rewrite an existing table.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `instrument_profiles` (
                `ticker` TEXT NOT NULL,
                `longName` TEXT, `exchange` TEXT, `instrumentType` TEXT, `currency` TEXT,
                `exchangeTimeZone` TEXT,
                `preStart` INTEGER, `preEnd` INTEGER,
                `regularStart` INTEGER, `regularEnd` INTEGER,
                `postStart` INTEGER, `postEnd` INTEGER,
                `fiftyTwoWeekHigh` REAL, `fiftyTwoWeekLow` REAL,
                `dayHigh` REAL, `dayLow` REAL,
                `volume` INTEGER, `firstTradeDate` INTEGER,
                `description` TEXT, `sector` TEXT, `industry` TEXT, `website` TEXT,
                `country` TEXT, `fundFamily` TEXT, `legalType` TEXT,
                `expenseRatio` REAL, `totalNetAssets` REAL, `category` TEXT,
                `fetchedAt` INTEGER NOT NULL,
                PRIMARY KEY(`ticker`)
            )
            """.trimIndent(),
        )
    }
}
