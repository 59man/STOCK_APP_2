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
        DailyAnchorEntity::class,
        PriceAlertEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class StockTrackerDatabase : RoomDatabase() {
    abstract fun portfolioDao(): PortfolioDao
    abstract fun positionDao(): PositionDao
    abstract fun manualPriceDao(): ManualPriceDao
    abstract fun divTaxOverrideDao(): DivTaxOverrideDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun instrumentProfileDao(): InstrumentProfileDao
    abstract fun dailyAnchorDao(): DailyAnchorDao
    abstract fun priceAlertDao(): PriceAlertDao
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

/**
 * Adds the midnight-anchor cache. One new table, nothing else touched — same reasoning as
 * [MIGRATION_1_2]: this database is the offline-first source of truth.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `daily_anchors` (
                `ticker` TEXT NOT NULL,
                `localDate` TEXT NOT NULL,
                `price` REAL,
                `lastTradedAt` INTEGER,
                PRIMARY KEY(`ticker`, `localDate`)
            )
            """.trimIndent(),
        )
    }
}

/** Adds device-local price alerts. One new table, nothing else touched. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `price_alerts` (
                `id` TEXT NOT NULL,
                `ticker` TEXT NOT NULL,
                `above` INTEGER NOT NULL,
                `threshold` REAL NOT NULL,
                `currency` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `armed` INTEGER NOT NULL,
                `lastTriggeredAt` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
    }
}
