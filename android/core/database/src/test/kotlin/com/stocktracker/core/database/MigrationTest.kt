package com.stocktracker.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val TEST_DB = "migration-test"

/**
 * The database holds positions and unresolved sync conflicts, so destructive fallback is not
 * enabled and a broken migration would be real data loss. This asserts the rows survive, not
 * merely that the migration runs.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StockTrackerDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_keepsExistingRowsAndAddsTheProfileTable() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO portfolios (id, name) VALUES ('p1', 'Main Portfolio')")
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.query("SELECT name FROM portfolios WHERE id = 'p1'").use { cursor ->
            assertTrue("the portfolio row did not survive the migration", cursor.moveToFirst())
            assertEquals("Main Portfolio", cursor.getString(0))
        }

        db.execSQL("INSERT INTO instrument_profiles (ticker, fetchedAt) VALUES ('AAPL', 1)")
        db.query("SELECT ticker FROM instrument_profiles").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("AAPL", cursor.getString(0))
        }
    }
}
