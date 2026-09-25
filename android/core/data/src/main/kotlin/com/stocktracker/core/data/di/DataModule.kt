package com.stocktracker.core.data.di

import android.content.Context
import androidx.room.Room
import com.stocktracker.core.data.SettingsRepository
import com.stocktracker.core.database.DivTaxOverrideDao
import com.stocktracker.core.database.ManualPriceDao
import com.stocktracker.core.database.PortfolioDao
import com.stocktracker.core.database.PositionDao
import com.stocktracker.core.database.InstrumentProfileDao
import com.stocktracker.core.network.createInstrumentProfileSource
import com.stocktracker.core.network.InstrumentProfileSource
import com.stocktracker.core.database.DailyAnchorDao
import com.stocktracker.core.database.MIGRATION_1_2
import com.stocktracker.core.database.MIGRATION_2_3
import com.stocktracker.core.database.MIGRATION_3_4
import com.stocktracker.core.database.PriceAlertDao
import com.stocktracker.core.database.StockTrackerDatabase
import com.stocktracker.core.database.SyncStateDao
import com.stocktracker.core.network.DeviceApi
import com.stocktracker.core.network.DividendClient
import com.stocktracker.core.network.DividendSource
import com.stocktracker.core.network.PersistApi
import com.stocktracker.core.network.PersistApiConfig
import com.stocktracker.core.network.createDeviceApi
import com.stocktracker.core.network.createPersistApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StockTrackerDatabase =
        Room.databaseBuilder(context, StockTrackerDatabase::class.java, "stock-tracker.db")
            // Real migrations, never fallbackToDestructiveMigration: this database is the
            // offline-first source of truth and holds unresolved sync conflicts.
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides fun providePortfolioDao(db: StockTrackerDatabase): PortfolioDao = db.portfolioDao()
    @Provides fun providePositionDao(db: StockTrackerDatabase): PositionDao = db.positionDao()
    @Provides fun provideManualPriceDao(db: StockTrackerDatabase): ManualPriceDao = db.manualPriceDao()
    @Provides fun provideInstrumentProfileDao(db: StockTrackerDatabase): InstrumentProfileDao = db.instrumentProfileDao()
    @Provides fun provideDailyAnchorDao(db: StockTrackerDatabase): DailyAnchorDao = db.dailyAnchorDao()
    @Provides fun providePriceAlertDao(db: StockTrackerDatabase): PriceAlertDao = db.priceAlertDao()

    /**
     * The other market-data clients are Kotlin objects called directly; this one is a class so
     * its crumb cache has a lifetime and the repository can be tested against a fake.
     */
    @Provides
    @Singleton
    fun provideInstrumentProfileSource(): InstrumentProfileSource = createInstrumentProfileSource()
    @Provides fun provideDivTaxOverrideDao(db: StockTrackerDatabase): DivTaxOverrideDao = db.divTaxOverrideDao()
    @Provides fun provideSyncStateDao(db: StockTrackerDatabase): SyncStateDao = db.syncStateDao()

    @Provides
    @Singleton
    fun provideDividendSource(): DividendSource = DividendClient

    @Provides
    @Singleton
    fun providePersistApi(settings: SettingsRepository): PersistApi {
        val config = object : PersistApiConfig {
            override suspend fun serverUrl(): String = settings.settings.first().serverUrl
            override suspend fun apiKey(): String = settings.settings.first().apiKey
        }
        return createPersistApi(config)
    }

    @Provides
    @Singleton
    fun provideDeviceApi(settings: SettingsRepository): DeviceApi {
        val config = object : PersistApiConfig {
            override suspend fun serverUrl(): String = settings.settings.first().serverUrl
            override suspend fun apiKey(): String = settings.settings.first().apiKey
        }
        return createDeviceApi(config)
    }
}
