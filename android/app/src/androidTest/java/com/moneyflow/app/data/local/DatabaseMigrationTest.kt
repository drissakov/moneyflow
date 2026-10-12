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

    @Test fun migrateTwoToThreePreservesOldTransactionsWithNoCategoryAndFxData() {
        val name = "moneyflow-categories-migration-test"
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO accounts VALUES ('user-a','account-a','Cash','KZT',500,400,'2026-10-09T00:00:00Z')")
            execSQL("INSERT INTO transactions VALUES ('user-a','transaction-a','account-a','expense',100,'KZT','Coffee','2026-10-09T00:00:00Z','2026-10-09T00:00:00Z')")
            execSQL("INSERT INTO fx_preferences VALUES ('user-a','USD',1)")
            execSQL("INSERT INTO portfolio_snapshots VALUES ('user-a','USD','snapshot','2026-10-09T00:00:00Z')")
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true, MoneyFlowDatabase.MIGRATION_2_3).apply {
            query("SELECT amountMinor,note,categoryId FROM transactions WHERE userId='user-a'").use {
                assertTrue(it.moveToFirst())
                assertEquals(100L, it.getLong(0))
                assertEquals("Coffee", it.getString(1))
                assertTrue(it.isNull(2))
            }
            query("SELECT baseCurrency,pendingSync FROM fx_preferences WHERE userId='user-a'").use {
                assertTrue(it.moveToFirst()); assertEquals("USD", it.getString(0)); assertEquals(1, it.getInt(1))
            }
            query("SELECT payload FROM portfolio_snapshots WHERE userId='user-a'").use {
                assertTrue(it.moveToFirst()); assertEquals("snapshot", it.getString(0))
            }
            close()
        }
    }

    @Test fun categoriesAreCachedAndDeletedPerUser() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            MoneyFlowDatabase::class.java).build()
        try {
            val dao = database.dao()
            dao.putCategories(listOf(
                CategoryEntity("user-a", "shared-id", "expense", "Продукты", null, true),
                CategoryEntity("user-b", "shared-id", "expense", "Продукты", null, true),
                CategoryEntity("user-a", "custom-id", "expense", "Кофе", "shared-id", false),
            ))
            assertEquals(2, dao.observeCategories("user-a").first().size)
            assertEquals(1, dao.observeCategories("user-b").first().size)
            assertEquals("shared-id", dao.category("user-a", "custom-id")?.parentId)
            assertNull(dao.category("user-b", "custom-id"))
            dao.deleteCategories("user-a")
            assertTrue(dao.observeCategories("user-a").first().isEmpty())
            assertEquals(1, dao.observeCategories("user-b").first().size)
        } finally { database.close() }
    }
}
