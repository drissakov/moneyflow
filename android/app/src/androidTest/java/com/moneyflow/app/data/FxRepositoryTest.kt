package com.moneyflow.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moneyflow.app.data.domain.User
import com.moneyflow.app.data.local.MoneyFlowDatabase
import com.moneyflow.app.data.remote.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class FxRepositoryTest {
    private val userId = "ee7d2d31-7369-4759-8125-f9d3967ded84"
    private val accountId = "ee7d2d31-7369-4759-8125-f9d3967ded85"

    private fun isolatedContext(): Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("fx_repository_test_$name", mode)
    }

    @Test fun rapidPreferenceChangesFinishWithTheLatestChoiceOnBothClientAndServer() = runBlocking {
        withTimeout(15_000) {
            val context = isolatedContext()
            context.getSharedPreferences("moneyflow_private", Context.MODE_PRIVATE).edit().clear().commit()
            val database = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var serverBase = "KZT"
            val calls = mutableListOf<String>()
            val api = object : StubApi() {
                override suspend fun login(credentials: Credentials) = auth(userId, credentials.email)
                override suspend fun preferences(authorization: String) = PreferencesDto(serverBase)
                override suspend fun setPreferences(body: PreferencesDto, authorization: String): PreferencesDto {
                    assertEquals("Bearer token-$userId", authorization)
                    calls += body.baseCurrency
                    if (body.baseCurrency == "USD") { started.complete(Unit); release.await() }
                    serverBase = body.baseCurrency
                    return body
                }
            }
            try {
                val repository = MoneyFlowRepository(database, SessionStore(context)) { api }
                repository.authenticate("test@example.com", "test-password-123", false)
                val first = async(Dispatchers.Default) { repository.setBaseCurrency("USD") }
                started.await()
                val second = async(Dispatchers.Default) { repository.setBaseCurrency("EUR") }
                database.dao().observePreferences(userId).first { it?.baseCurrency == "EUR" }
                release.complete(Unit)
                first.await(); second.await()
                assertEquals(listOf("USD", "EUR"), calls)
                assertEquals("EUR", serverBase)
                val local = database.dao().preferences(userId)!!
                assertEquals("EUR", local.baseCurrency)
                assertFalse(local.pendingSync)
            } finally {
                release.complete(Unit)
                database.close()
                context.getSharedPreferences("moneyflow_private", Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }

    @Test fun anInFlightPreWritePortfolioCannotRepopulateAnInvalidatedSnapshot() = runBlocking {
        withTimeout(15_000) {
            val context = isolatedContext()
            context.getSharedPreferences("moneyflow_private", Context.MODE_PRIVATE).edit().clear().commit()
            val database = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val timestamp = "2026-10-11T00:00:00Z"
            val api = object : StubApi() {
                override suspend fun login(credentials: Credentials) = auth(userId, credentials.email)
                override suspend fun createAccount(body: CreateAccountRequest) =
                    AccountDto(accountId, body.name, body.currency, body.openingBalanceMinor, body.openingBalanceMinor, timestamp)
                override suspend fun portfolio(base: String, authorization: String): PortfolioDto {
                    started.complete(Unit); release.await()
                    return PortfolioDto("KZT", "Frankfurter", null, false, true, "100", "100", emptyList(),
                        listOf(PortfolioAccountDto(accountId, "Cash", "KZT", "100", "100", "1", null)))
                }
                override suspend fun createTransaction(key: String, body: CreateTransactionRequest, authorization: String?) = TransactionDto(
                    "ee7d2d31-7369-4759-8125-f9d3967ded86", body.accountId, body.kind, body.amountMinor,
                    "KZT", body.note, body.occurredAt, timestamp)
            }
            try {
                val repository = MoneyFlowRepository(database, SessionStore(context)) { api }
                repository.authenticate("test@example.com", "test-password-123", false)
                repository.createAccount(CreateAccountRequest("Cash", "KZT", "100"))
                val oldRead = async(Dispatchers.Default) { repository.refreshPortfolio("KZT") }
                started.await()
                repository.createTransaction(CreateTransactionRequest(accountId, "expense", "1", "Coffee", timestamp))
                release.complete(Unit); oldRead.await()
                assertNull(repository.observePortfolio(userId, "KZT").first())
                // The old account balance may still be in Room until its independent refresh completes.
                assertEquals(100L, repository.observeAccounts(userId).first().single().balanceMinor)
            } finally {
                release.complete(Unit)
                database.close()
                context.getSharedPreferences("moneyflow_private", Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }

    private fun auth(id: String, email: String) = AuthResponse("token-$id", Instant.now().plusSeconds(3600).toString(), User(id, email))

    private abstract class StubApi : MoneyFlowApi {
        override suspend fun register(credentials: Credentials): AuthResponse = error("Unused API")
        override suspend fun login(credentials: Credentials): AuthResponse = error("Unused API")
        override suspend fun logout(authorization: String?) = Unit
        override suspend fun me(): User = error("Unused API")
        override suspend fun accounts(): AccountsResponse = error("Unused API")
        override suspend fun createAccount(body: CreateAccountRequest): AccountDto = error("Unused API")
        override suspend fun transactions(accountId: String, limit: Int): TransactionsResponse = error("Unused API")
        override suspend fun createTransaction(key: String, body: CreateTransactionRequest, authorization: String?): TransactionDto = error("Unused API")
        override suspend fun categories(authorization: String): CategoriesResponse = error("Unused API")
        override suspend fun createCategory(body: CreateCategoryRequest, authorization: String): CategoryDto = error("Unused API")
        override suspend fun setTransactionCategory(transactionId: String, body: SetTransactionCategoryRequest, authorization: String): TransactionDto = error("Unused API")
        override suspend fun analytics(month: String, currency: String, timezone: String, authorization: String): MonthlyAnalyticsDto = error("Unused API")
        override suspend fun currencies(authorization: String): CurrencyCatalogueDto = error("Unused API")
        override suspend fun preferences(authorization: String): PreferencesDto = error("Unused API")
        override suspend fun setPreferences(body: PreferencesDto, authorization: String): PreferencesDto = error("Unused API")
        override suspend fun exchangeRates(base: String, authorization: String): ExchangeRatesDto = error("Unused API")
        override suspend fun convert(body: ConvertRequest, authorization: String): ConversionDto = error("Unused API")
        override suspend fun portfolio(base: String, authorization: String): PortfolioDto = error("Unused API")
    }
}
