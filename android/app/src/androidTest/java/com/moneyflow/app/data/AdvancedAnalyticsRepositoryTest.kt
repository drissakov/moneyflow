package com.moneyflow.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.moneyflow.app.data.domain.AdvancedAnalyticsFilter
import com.moneyflow.app.data.domain.User
import com.moneyflow.app.data.local.AnalyticsSnapshotEntity
import com.moneyflow.app.data.local.MoneyFlowDatabase
import com.moneyflow.app.data.remote.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AdvancedAnalyticsRepositoryTest {
    private val user = "a5555555-5555-4555-8555-555555555555"
    private val account = AdvancedAnalyticsTestFixtures.ACCOUNT
    private val transaction = "c5555555-5555-4555-8555-555555555555"
    private val category = "d5555555-5555-4555-8555-555555555555"
    private val other = "e5555555-5555-4555-8555-555555555555"
    private val filter = AdvancedAnalyticsFilter("2026-10", "KZT", 6)

    @Test fun reportCacheUsesExactOwnerCurrencySpanAndAccountAndIsClearedAtLogout() = runBlocking {
        Fixture().use { fixture ->
            fixture.server.enqueue(json(AdvancedAnalyticsTestFixtures.report(filter.month)))
            val fresh = fixture.repo.advancedAnalytics(filter)
            assertEquals(fresh.report, fixture.repo.cachedAdvancedAnalytics(filter)?.report)
            assertNull(fixture.repo.cachedAdvancedAnalytics(filter.copy(currency = "USD")))
            assertNull(fixture.repo.cachedAdvancedAnalytics(filter.copy(months = 3)))
            assertNull(fixture.repo.cachedAdvancedAnalytics(filter.copy(accountId = account)))
            fixture.server.enqueue(json("""{"error":{"code":"unavailable","message":"Offline"}}""").setResponseCode(503))
            try { fixture.repo.advancedAnalytics(filter); fail("Expected unavailable") } catch (_: HttpException) { }
            assertEquals(fresh.report, fixture.repo.cachedAdvancedAnalytics(filter)?.report)
            fixture.db.dao().putAnalyticsSnapshot(AnalyticsSnapshotEntity(other, Gson().toJson(filter), "other-cache", Instant.now().toString()))
            fixture.repo.expireSession(fixture.repo.session.value)
            assertNull(fixture.db.dao().analyticsSnapshot(user, Gson().toJson(filter)))
            assertEquals("other-cache", fixture.db.dao().analyticsSnapshot(other, Gson().toJson(filter))?.payload)
        }
    }

    @Test fun inFlightReportCannotRepopulateCacheAfterAcknowledgedFinancialMutation() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/v1/analytics/dashboard" -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); json(AdvancedAnalyticsTestFixtures.report(filter.month)) }
                    "/api/v1/transactions/$transaction" -> json(transactionJson())
                    else -> MockResponse().setResponseCode(404)
                }
            }
            try {
                supervisorScope {
                    val read = async { runCatching { fixture.repo.advancedAnalytics(filter) } }
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    fixture.repo.mutateLedger("edittransaction", transaction, JsonParser.parseString("""{"expected_version":1,"account_id":"$account","kind":"expense","amount_minor":"100","note":"Changed","occurred_at":"2026-10-10T00:00:00Z","category_id":null,"exclude_from_analytics":false}""").asJsonObject)
                    release.countDown()
                    assertTrue(withTimeout(5_000) { read.await().exceptionOrNull() } is IllegalStateException)
                    assertNull(fixture.repo.cachedAdvancedAnalytics(filter))
                    assertNull(fixture.repo.ledgerPending.value)
                }
            } finally { release.countDown() }
        }
    }

    @Test fun ignoredDrillDownFiltersAreRejectedInsteadOfCachingUnreconciledTransactions() = runBlocking {
        Fixture().use { fixture ->
            val exact = LedgerFilter(currency = "KZT", analyticsOnly = true, categoryId = category, categoryExact = true, kind = "expense")
            listOf(transactionJson(currency = "USD", categoryId = category), transactionJson(categoryId = category, excluded = true), transactionJson()).forEach { row ->
                fixture.server.enqueue(json("""{"transactions":[$row],"next_cursor":null}"""))
                try { fixture.repo.history(exact); fail("Mismatched report facts must be rejected") } catch (_: IllegalArgumentException) { }
                assertNull(fixture.repo.cachedHistory(exact))
            }
            val without = LedgerFilter(currency = "KZT", analyticsOnly = true, uncategorized = true)
            fixture.server.enqueue(json("""{"transactions":[${transactionJson(categoryId = category)}],"next_cursor":null}"""))
            try { fixture.repo.history(without); fail("Categorized row must not appear under uncategorized") } catch (_: IllegalArgumentException) { }
            assertNull(fixture.repo.cachedHistory(without))
            fixture.server.enqueue(json("""{"transactions":[],"next_cursor":null}"""))
            fixture.repo.history(exact)
            assertTrue(requireNotNull(fixture.repo.cachedHistory(exact)).isEmpty())
            assertNull(fixture.repo.cachedHistory(exact.copy(categoryExact = false)))
        }
    }

    @Test fun responseCapturedBeforeAnotherOwnerLoginCannotEnterEitherOwnersCache() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture().use { fixture ->
            val expires = Instant.now().plusSeconds(3600).toString()
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/v1/analytics/dashboard" -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); json(AdvancedAnalyticsTestFixtures.report(filter.month)) }
                    "/api/v1/auth/login" -> json("""{"access_token":"other-owner-token","expires_at":"$expires","user":{"id":"$other","email":"other@example.com"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            try {
                supervisorScope {
                    val read = async { runCatching { fixture.repo.advancedAnalytics(filter) } }
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    val original = fixture.repo.session.value
                    fixture.repo.expireSession(original)
                    fixture.repo.authenticate("other@example.com", "synthetic-long-password", false)
                    release.countDown()
                    assertTrue(withTimeout(5_000) { read.await().exceptionOrNull() } is IllegalStateException)
                    assertEquals(other, fixture.repo.session.value?.user?.id)
                    assertNull(fixture.db.dao().analyticsSnapshot(user, Gson().toJson(filter)))
                    assertNull(fixture.repo.cachedAdvancedAnalytics(filter))
                    val captured = fixture.server.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals("Bearer analytics-owner", captured.getHeader("Authorization"))
                }
            } finally { release.countDown() }
        }
    }

    private class Recovery : LedgerRecoveryStorage {
        var pending: PendingLedgerMutation? = null
        override fun read(userId: String): PendingLedgerMutation? = pending?.takeIf { it.userId == userId }
        override fun write(action: PendingLedgerMutation) { pending = action }
        override fun clear(userId: String, expectedKey: String) { if (pending?.userId == userId && pending?.key == expectedKey) pending = null }
    }
    private inner class Fixture : AutoCloseable {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            private val prefix = "advanced_analytics_${UUID.randomUUID()}_"
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
        val store = SessionStore(context)
        val server = MockWebServer().apply { start() }
        init { store.writeSession(Session("analytics-owner", Instant.now().plusSeconds(3600).toString(), User(user, "analytics@example.com"))) }
        val repo = MoneyFlowRepository(db, store, ledgerRecovery = Recovery()) { ApiFactory.create(server.url("/").toString()) { null } }
        override fun close() { store.clearSession(); db.close(); server.close() }
    }
    private fun transactionJson(currency: String = "KZT", categoryId: String? = null, excluded: Boolean = false): String =
        """{"id":"$transaction","account_id":"$account","kind":"expense","amount_minor":"100","currency":"$currency","note":"Changed","occurred_at":"2026-10-10T00:00:00Z","created_at":"2026-10-10T00:00:00Z","version":2,"category_id":${categoryId?.let { "\"$it\"" } ?: "null"},"exclude_from_analytics":$excluded,"source":"manual"}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
