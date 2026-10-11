package com.moneyflow.app.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MoneyFlowDatabase::class.java)

    @Test fun migrateOneToTwoPreservesExistingAccountsAndTransactions() {
        val name = "moneyflow-migration-test" // Dedicated test database; never the user's moneyflow.db.
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO accounts VALUES ('user-a','account-a','Savings','KWD',1234,1233,'2026-10-09T00:00:00Z')")
            execSQL("INSERT INTO accounts VALUES ('user-b','account-b','Cash','JPY',125,125,'2026-10-09T00:00:00Z')")
            execSQL("INSERT INTO transactions VALUES ('user-a','transaction-a','account-a','expense',1,'KWD','Coffee','2026-10-09T00:00:00Z','2026-10-09T00:00:00Z')")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, MoneyFlowDatabase.MIGRATION_1_2).apply {
            query("SELECT name,currency,balanceMinor FROM accounts WHERE userId='user-a'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Savings", it.getString(0))
                assertEquals("KWD", it.getString(1))
                assertEquals(1233L, it.getLong(2))
            }
            query("SELECT amountMinor,note FROM transactions WHERE userId='user-a'").use {
                assertTrue(it.moveToFirst())
                assertEquals(1L, it.getLong(0))
                assertEquals("Coffee", it.getString(1))
            }
            query("SELECT COUNT(*) FROM accounts").use { assertTrue(it.moveToFirst()); assertEquals(2L, it.getLong(0)) }
            close()
        }
    }

    @Test fun preferencesAndPortfolioSnapshotsAreIsolatedByUserAndBase() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            MoneyFlowDatabase::class.java).build()
        try {
            val dao = database.dao()
            dao.putPreferences(FxPreferencesEntity("user-a", "USD", true))
            dao.putPreferences(FxPreferencesEntity("user-b", "JPY", false))
            assertEquals("USD", dao.observePreferences("user-a").first()?.baseCurrency)
            assertEquals("JPY", dao.observePreferences("user-b").first()?.baseCurrency)
            dao.putPortfolio(PortfolioSnapshotEntity("user-a", "USD", "snapshot-a-usd", "2026-10-11T00:00:00Z"))
            dao.putPortfolio(PortfolioSnapshotEntity("user-a", "JPY", "snapshot-a-jpy", "2026-10-11T00:00:00Z"))
            dao.putPortfolio(PortfolioSnapshotEntity("user-b", "USD", "snapshot-b-usd", "2026-10-11T00:00:00Z"))
            assertEquals("snapshot-a-jpy", dao.observePortfolio("user-a", "JPY").first()?.payload)
            assertEquals("snapshot-b-usd", dao.observePortfolio("user-b", "USD").first()?.payload)
            dao.deletePortfolios("user-a")
            assertNull(dao.observePortfolio("user-a", "USD").first())
            assertNotNull(dao.observePortfolio("user-b", "USD").first())
        } finally { database.close() }
    }
}
