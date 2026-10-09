package com.moneyflow.app.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.MoneyTransaction
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
) {
    fun toDomain() = MoneyTransaction(id, accountId, kind, amountMinor, currency, note, occurredAt, createdAt)
    companion object {
        fun from(userId: String, transaction: MoneyTransaction) = TransactionEntity(
            userId, transaction.id, transaction.accountId, transaction.kind, transaction.amountMinor,
            transaction.currency, transaction.note, transaction.occurredAt, transaction.createdAt,
        )
    }
}

@Dao
interface MoneyFlowDao {
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

@Database(entities = [AccountEntity::class, TransactionEntity::class], version = 1, exportSchema = true)
abstract class MoneyFlowDatabase : RoomDatabase() {
    abstract fun dao(): MoneyFlowDao
}
