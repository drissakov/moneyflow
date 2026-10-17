package com.moneyflow.app.data.local

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.MoneyTransaction
import com.moneyflow.app.data.domain.Category
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts", primaryKeys = ["userId", "id"])
data class AccountEntity(
    val userId: String,
    val id: String,
    val name: String,
    val currency: String,
    val openingBalanceMinor: Long,
    val balanceMinor: Long,
    val createdAt: String,
    @ColumnInfo(defaultValue = "'card'") val type: String = "card",
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    @ColumnInfo(defaultValue = "1") val includeInBalance: Boolean = true,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "1") val version: Long = 1,
) {
    fun toDomain() = Account(id, name, currency, openingBalanceMinor, balanceMinor, createdAt, type, archived, includeInBalance, sortOrder, version)
    companion object {
        fun from(userId: String, account: Account) = AccountEntity(
            userId, account.id, account.name, account.currency,
            account.openingBalanceMinor, account.balanceMinor, account.createdAt, account.type, account.archived,
            account.includeInBalance, account.sortOrder, account.version,
        )
    }
}

@Entity(tableName = "transactions", primaryKeys = ["userId", "id"], indices = [Index("userId", "accountId")])
data class TransactionEntity(
    val userId: String,
    val id: String,
    val accountId: String,
    val kind: String,
    val amountMinor: Long,
    val currency: String,
    val note: String,
    val occurredAt: String,
    val createdAt: String,
    val categoryId: String? = null,
    @ColumnInfo(defaultValue = "0") val excludeFromAnalytics: Boolean = false,
    @ColumnInfo(defaultValue = "1") val version: Long = 1,
    val deletedAt: String? = null,
    @ColumnInfo(defaultValue = "'manual'") val source: String = "manual",
    val importId: String? = null,
    val transferId: String? = null,
    val transferRole: String? = null,
) {
    fun toDomain() = MoneyTransaction(id, accountId, kind, amountMinor, currency, note, occurredAt, createdAt, categoryId, excludeFromAnalytics, version, deletedAt, source, importId, transferId, transferRole)
    companion object {
        fun from(userId: String, transaction: MoneyTransaction) = TransactionEntity(
            userId, transaction.id, transaction.accountId, transaction.kind, transaction.amountMinor,
            transaction.currency, transaction.note, transaction.occurredAt, transaction.createdAt, transaction.categoryId, transaction.excludeFromAnalytics, transaction.version, transaction.deletedAt,
            transaction.source, transaction.importId, transaction.transferId, transaction.transferRole,
        )
    }
}

@Entity(tableName = "categories", primaryKeys = ["userId", "id"], indices = [Index("userId", "parentId")])
data class CategoryEntity(
    val userId: String,
    val id: String,
    val kind: String,
    val name: String,
    val parentId: String?,
    val isDefault: Boolean,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "1") val version: Long = 1,
) {
    fun toDomain() = Category(id, kind, name, parentId, isDefault, archived, sortOrder, version)
    companion object {
        fun from(userId: String, category: Category) = CategoryEntity(
            userId, category.id, category.kind, category.name, category.parentId, category.isDefault, category.archived, category.sortOrder, category.version,
        )
    }
}

@Entity(tableName = "fx_preferences", primaryKeys = ["userId"])
data class FxPreferencesEntity(val userId: String, val baseCurrency: String, val pendingSync: Boolean)

@Entity(tableName = "portfolio_snapshots", primaryKeys = ["userId", "baseCurrency"])
data class PortfolioSnapshotEntity(
    val userId: String,
    val baseCurrency: String,
    val payload: String,
    val receivedAt: String,
)

@Entity(tableName = "ledger_snapshots", primaryKeys = ["userId", "filterKey"])
data class LedgerSnapshotEntity(val userId: String, val filterKey: String, val payload: String, val receivedAt: String)

/** A read-only server calculation. Financial writes never use this cached payload as input. */
@Entity(tableName = "planning_snapshots", primaryKeys = ["userId", "month", "forecastUntil"])
data class PlanningSnapshotEntity(val userId: String, val month: String, val forecastUntil: String,
    val payload: String, val receivedAt: String)

@Entity(tableName = "analytics_snapshots", primaryKeys = ["userId", "filterKey"])
data class AnalyticsSnapshotEntity(val userId: String, val filterKey: String, val payload: String, val receivedAt: String)

@Dao
interface MoneyFlowDao {
    @Query("SELECT * FROM analytics_snapshots WHERE userId = :userId AND filterKey = :filterKey LIMIT 1")
    suspend fun analyticsSnapshot(userId: String, filterKey: String): AnalyticsSnapshotEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAnalyticsSnapshot(snapshot: AnalyticsSnapshotEntity)
    @Query("DELETE FROM analytics_snapshots WHERE userId = :userId")
    suspend fun deleteAnalyticsSnapshots(userId: String)
    @Query("DELETE FROM analytics_snapshots WHERE userId = :userId AND filterKey NOT IN (SELECT filterKey FROM analytics_snapshots WHERE userId = :userId ORDER BY receivedAt DESC LIMIT 20)")
    suspend fun pruneAnalyticsSnapshots(userId: String)
    @Query("SELECT * FROM planning_snapshots WHERE userId = :userId AND month = :month AND forecastUntil = :forecastUntil LIMIT 1")
    suspend fun planningSnapshot(userId: String, month: String, forecastUntil: String): PlanningSnapshotEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPlanningSnapshot(snapshot: PlanningSnapshotEntity)
    @Query("DELETE FROM planning_snapshots WHERE userId = :userId")
    suspend fun deletePlanningSnapshots(userId: String)
    @Query("DELETE FROM planning_snapshots WHERE userId = :userId AND (month || ':' || forecastUntil) NOT IN (SELECT month || ':' || forecastUntil FROM planning_snapshots WHERE userId = :userId ORDER BY receivedAt DESC LIMIT 12)")
    suspend fun prunePlanningSnapshots(userId: String)
    @Query("SELECT * FROM ledger_snapshots WHERE userId = :userId AND filterKey = :filterKey LIMIT 1")
    suspend fun ledgerSnapshot(userId: String, filterKey: String): LedgerSnapshotEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLedgerSnapshot(snapshot: LedgerSnapshotEntity)
    @Query("DELETE FROM ledger_snapshots WHERE userId = :userId")
    suspend fun deleteLedgerSnapshots(userId: String)
    @Query("DELETE FROM ledger_snapshots WHERE userId = :userId AND filterKey NOT IN (SELECT filterKey FROM ledger_snapshots WHERE userId = :userId ORDER BY receivedAt DESC LIMIT 20)")
    suspend fun pruneLedgerSnapshots(userId: String)

    @Query("SELECT * FROM categories WHERE userId = :userId ORDER BY kind ASC, sortOrder ASC, name COLLATE NOCASE ASC, id ASC")
    fun observeCategories(userId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE userId = :userId AND id = :categoryId LIMIT 1")
    suspend fun category(userId: String, categoryId: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCategories(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE userId = :userId")
    suspend fun deleteCategories(userId: String)

    @Query("SELECT * FROM fx_preferences WHERE userId = :userId LIMIT 1")
    fun observePreferences(userId: String): Flow<FxPreferencesEntity?>

    @Query("SELECT * FROM fx_preferences WHERE userId = :userId LIMIT 1")
    suspend fun preferences(userId: String): FxPreferencesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPreferences(preferences: FxPreferencesEntity)

    @Query("SELECT * FROM portfolio_snapshots WHERE userId = :userId AND baseCurrency = :base LIMIT 1")
    fun observePortfolio(userId: String, base: String): Flow<PortfolioSnapshotEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPortfolio(snapshot: PortfolioSnapshotEntity)

    @Query("DELETE FROM portfolio_snapshots WHERE userId = :userId")
    suspend fun deletePortfolios(userId: String)

    @Query("DELETE FROM fx_preferences WHERE userId = :userId")
    suspend fun deletePreferences(userId: String)

    @Query("SELECT * FROM accounts WHERE userId = :userId ORDER BY sortOrder ASC, createdAt ASC, id ASC")
    fun observeAccounts(userId: String): Flow<List<AccountEntity>>

    @Query("SELECT * FROM transactions WHERE userId = :userId AND accountId = :accountId AND deletedAt IS NULL ORDER BY occurredAt DESC, id DESC LIMIT 50")
    fun observeTransactions(userId: String, accountId: String): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAccounts(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTransactions(transactions: List<TransactionEntity>)

    @Query("DELETE FROM accounts WHERE userId = :userId")
    suspend fun deleteAccounts(userId: String)

    @Query("DELETE FROM transactions WHERE userId = :userId AND accountId = :accountId")
    suspend fun deleteTransactions(userId: String, accountId: String)

    @Query("DELETE FROM transactions WHERE userId = :userId")
    suspend fun deleteUserTransactions(userId: String)
}

@Database(entities = [AccountEntity::class, TransactionEntity::class, FxPreferencesEntity::class,
    PortfolioSnapshotEntity::class, CategoryEntity::class, LedgerSnapshotEntity::class, PlanningSnapshotEntity::class,
    AnalyticsSnapshotEntity::class], version = 7, exportSchema = true)
abstract class MoneyFlowDatabase : RoomDatabase() {
    abstract fun dao(): MoneyFlowDao

    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `analytics_snapshots` (`userId` TEXT NOT NULL, `filterKey` TEXT NOT NULL, `payload` TEXT NOT NULL, `receivedAt` TEXT NOT NULL, PRIMARY KEY(`userId`, `filterKey`))")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `planning_snapshots` (`userId` TEXT NOT NULL, `month` TEXT NOT NULL, `forecastUntil` TEXT NOT NULL, `payload` TEXT NOT NULL, `receivedAt` TEXT NOT NULL, PRIMARY KEY(`userId`, `month`, `forecastUntil`))")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'card'")
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `includeInBalance` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `version` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `categories` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `categories` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `categories` ADD COLUMN `version` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `version` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `deletedAt` TEXT")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'manual'")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `importId` TEXT")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `transferId` TEXT")
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `transferRole` TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `ledger_snapshots` (`userId` TEXT NOT NULL, `filterKey` TEXT NOT NULL, `payload` TEXT NOT NULL, `receivedAt` TEXT NOT NULL, PRIMARY KEY(`userId`, `filterKey`))")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `fx_preferences` (`userId` TEXT NOT NULL, `baseCurrency` TEXT NOT NULL, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`userId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `portfolio_snapshots` (`userId` TEXT NOT NULL, `baseCurrency` TEXT NOT NULL, `payload` TEXT NOT NULL, `receivedAt` TEXT NOT NULL, PRIMARY KEY(`userId`, `baseCurrency`))")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `categoryId` TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`userId` TEXT NOT NULL, `id` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `parentId` TEXT, `isDefault` INTEGER NOT NULL, PRIMARY KEY(`userId`, `id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_userId_parentId` ON `categories` (`userId`, `parentId`)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `excludeFromAnalytics` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
