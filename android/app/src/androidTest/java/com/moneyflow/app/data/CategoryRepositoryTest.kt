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
import kotlinx.coroutines.CoroutineStart
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
class CategoryRepositoryTest {
    private val userA = "ee7d2d31-7369-4759-8125-f9d3967ded84"
    private val userB = "ee7d2d31-7369-4759-8125-f9d3967ded85"
    private val categoryId = "ee7d2d31-7369-4759-8125-f9d3967ded86"

    @Test fun customCategoryWithRepeatedUnicodeWhitespaceIsSubmittedAndCachedWithTheCanonicalName() = runBlocking {
        val context = isolatedContext()
        val preferences = context.getSharedPreferences("moneyflow_private", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val database = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
        val api = object : StubApi() {
            override suspend fun createCategory(body: CreateCategoryRequest, authorization: String): CategoryDto {
                assertEquals("Покупка автомобиля", body.name)
                return CategoryDto(categoryId, body.kind, "Покупка автомобиля", body.parentId, false)
            }
        }
        try {
            val repository = MoneyFlowRepository(database, SessionStore(context)) { api }
            repository.authenticate("a@example.com", "test-password-123", false)
            val category = repository.createCategory(CreateCategoryRequest("expense", "\u0085Покупка   \u2003автомобиля\u00a0"))
            assertEquals("Покупка автомобиля", category.name)
            assertEquals(category, repository.observeCategories(userA).first().single())
        } finally {
            database.close()
            preferences.edit().clear().commit()
        }
    }

    @Test fun lateCategoryMutationAndAnalyticsCannotPopulateAnotherSession() = runBlocking {
        withTimeout(15_000) {
            val context = isolatedContext()
            val preferences = context.getSharedPreferences("moneyflow_private", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val database = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
            val categoryStarted = CompletableDeferred<Unit>()
            val analyticsStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val api = object : StubApi() {
                override suspend fun createCategory(body: CreateCategoryRequest, authorization: String): CategoryDto {
                    assertEquals("Bearer token-$userA", authorization)
                    categoryStarted.complete(Unit); release.await()
                    return CategoryDto(categoryId, body.kind, body.name, body.parentId, false)
                }
                override suspend fun analytics(month: String, currency: String, timezone: String, authorization: String): MonthlyAnalyticsDto {
                    assertEquals("Bearer token-$userA", authorization)
                    analyticsStarted.complete(Unit); release.await()
                    return MonthlyAnalyticsDto(month, currency, timezone, "0", "0", emptyList(), emptyList())
                }
            }
            try {
                val repository = MoneyFlowRepository(database, SessionStore(context)) { api }
                repository.authenticate("a@example.com", "test-password-123", false)
                val category = async(Dispatchers.Default) { runCatching { repository.createCategory(CreateCategoryRequest("expense", "Автомобиль")) } }
                val analytics = async(Dispatchers.Default) { runCatching { repository.fetchAnalytics("2026-10", "KZT", "Asia/Qyzylorda") } }
                categoryStarted.await(); analyticsStarted.await()
                repository.logout()
                repository.authenticate("b@example.com", "test-password-123", false)
                release.complete(Unit)
                assertTrue(category.await().exceptionOrNull() is IllegalStateException)
                assertTrue(analytics.await().exceptionOrNull() is IllegalStateException)
                assertEquals(userB, repository.session.value!!.user.id)
                assertTrue(repository.observeCategories(userA).first().isEmpty())
                assertTrue(repository.observeCategories(userB).first().isEmpty())
            } finally {
                release.complete(Unit)
                database.close()
                preferences.edit().clear().commit()
            }
        }
    }

    @Test fun queuedCategoryMutationCannotUseAReplacementSession() = runBlocking {
        withTimeout(15_000) {
            val context = isolatedContext()
            val preferences = context.getSharedPreferences("moneyflow_private", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            val database = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var categoryPosts = 0
            val api = object : StubApi() {
                override suspend fun categories(authorization: String): CategoriesResponse {
                    started.complete(Unit); release.await()
                    return CategoriesResponse(emptyList())
                }
                override suspend fun createCategory(body: CreateCategoryRequest, authorization: String): CategoryDto {
                    categoryPosts++
                    return CategoryDto(categoryId, body.kind, body.name, body.parentId, false)
                }
            }
            try {
                val repository = MoneyFlowRepository(database, SessionStore(context)) { api }
                repository.authenticate("a@example.com", "test-password-123", false)
                val refresh = async(Dispatchers.Default) { runCatching { repository.refreshCategories() } }
                started.await()
                // Enter synchronously so the queued mutation captures user A before waiting for the mutex.
                val create = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { repository.createCategory(CreateCategoryRequest("income", "Грант")) }
                }
                repository.logout()
                repository.authenticate("b@example.com", "test-password-123", false)
                release.complete(Unit)
                assertTrue(refresh.await().isFailure)
                assertTrue(create.await().exceptionOrNull() is IllegalStateException)
                assertEquals(0, categoryPosts)
                assertEquals(userB, repository.session.value!!.user.id)
            } finally {
                release.complete(Unit)
                database.close()
                preferences.edit().clear().commit()
            }
        }
    }

    private fun isolatedContext(): Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("category_repository_test_$name", mode)
    }

    private abstract inner class StubApi : MoneyFlowApi {
        override suspend fun login(credentials: Credentials): AuthResponse {
            val id = if (credentials.email.startsWith("a@")) userA else userB
            return AuthResponse("token-$id", Instant.now().plusSeconds(3600).toString(), User(id, credentials.email))
        }
        override suspend fun register(credentials: Credentials): AuthResponse = error("Unused API")
        override suspend fun logout(authorization: String?) = Unit
        override suspend fun me(): User = error("Unused API")
        override suspend fun accounts(): AccountsResponse = error("Unused API")
        override suspend fun createAccount(body: CreateAccountRequest, authorization: String?): AccountDto = error("Unused API")
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
