package com.moneyflow.app.data.local

import androidx.room.Dao
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
) {
    fun toDomain() = Account(id, name, currency, openingBalanceMinor, balanceMinor, createdAt)
    companion object {
        fun from(userId: String, account: Account) = AccountEntity(
            userId, account.id, account.name, account.currency,
            account.openingBalanceMinor, account.balanceMinor, account.createdAt,
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
) {
    fun toDomain() = MoneyTransaction(id, accountId, kind, amountMinor, currency, note, occurredAt, createdAt, categoryId)
    companion object {
        fun from(userId: String, transaction: MoneyTransaction) = TransactionEntity(
            userId, transaction.id, transaction.accountId, transaction.kind, transaction.amountMinor,
            transaction.currency, transaction.note, transaction.occurredAt, transaction.createdAt, transaction.categoryId,
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
) {
    fun toDomain() = Category(id, kind, name, parentId, isDefault)
    companion object {
        fun from(userId: String, category: Category) = CategoryEntity(
            userId, category.id, category.kind, category.name, category.parentId, category.isDefault,
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

@Dao
interface MoneyFlowDao {
    @Query("SELECT * FROM categories WHERE userId = :userId ORDER BY kind ASC, name COLLATE NOCASE ASC, id ASC")
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

    @Query("SELECT * FROM accounts WHERE userId = :userId ORDER BY createdAt ASC, id ASC")
    fun observeAccounts(userId: String): Flow<List<AccountEntity>>

    @Query("SELECT * FROM transactions WHERE userId = :userId AND accountId = :accountId ORDER BY occurredAt DESC, id DESC LIMIT 50")
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
    PortfolioSnapshotEntity::class, CategoryEntity::class], version = 3, exportSchema = true)
abstract class MoneyFlowDatabase : RoomDatabase() {
    abstract fun dao(): MoneyFlowDao

    companion object {
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
    }
}
