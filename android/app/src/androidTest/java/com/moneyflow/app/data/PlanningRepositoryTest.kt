package com.moneyflow.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.moneyflow.app.data.domain.User
import com.moneyflow.app.data.local.MoneyFlowDatabase
import com.moneyflow.app.data.local.PlanningSnapshotEntity
import com.moneyflow.app.data.local.FxPreferencesEntity
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
class PlanningRepositoryTest {
    private val user = "a8888888-8888-4888-8888-888888888888"
    private val account = "b8888888-8888-4888-8888-888888888888"
    private val expires = Instant.now().plusSeconds(3600).toString()
    private val settings = """{"expected_version":0,"reserve_minor":"100","account_ids":[],"base_currency":"KZT"}"""
    private val ack = """{"version":1,"reserve_minor":"100","account_ids":[],"base_currency":"KZT"}"""

    private class Recovery : LedgerRecoveryStorage {
        var pending: PendingLedgerMutation? = null
        override fun read(userId: String) = pending?.takeIf { it.userId == userId }
        override fun write(action: PendingLedgerMutation) { check(pending == null || pending == action); pending = action }
        override fun clear(userId: String, expectedKey: String) { if (pending?.userId == userId && pending?.key == expectedKey) pending = null }
    }

    @Test fun uncertainPlanningRequestSurvivesRestartBlocksLegacyAndReplaysExactBody() = runBlocking {
        val fixture = Fixture()
        fixture.use {
            fixture.server.enqueue(json("""{"error":{"code":"unavailable","message":"Retry"}}""").setResponseCode(503))
            try { fixture.repo.mutateLedger("editplanningsettings", null, JsonParser.parseString(settings).asJsonObject); fail("Expected uncertain failure") }
            catch (_: HttpException) { }
            val saved = requireNotNull(fixture.recovery.pending)
            val restarted = fixture.newRepository()
            assertEquals(saved, restarted.ledgerPending.value)
            try { restarted.createAccount(CreateAccountRequest("Cash", "KZT", "0")); fail("Pending planning write must block legacy API") }
            catch (_: IllegalArgumentException) { }
            assertEquals(1, fixture.server.requestCount)
            fixture.db.dao().putPlanningSnapshot(PlanningSnapshotEntity(user, "2026-10", "", "outdated", "2026-10-17T00:00:00Z"))
            fixture.server.enqueue(json(ack))
            restarted.retryLedgerMutation()
            assertNull(restarted.ledgerPending.value); assertNull(fixture.recovery.pending)
            assertNull(fixture.db.dao().planningSnapshot(user, "2026-10", ""))
            val first = fixture.server.takeRequest(1, TimeUnit.SECONDS)!!
            val retry = fixture.server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("PUT", retry.method); assertEquals("/api/v1/planning/settings", retry.path)
            assertEquals(saved.body, first.body.readUtf8()); assertEquals(saved.body, retry.body.readUtf8())
            assertEquals(saved.key, first.getHeader("Idempotency-Key")); assertEquals(saved.key, retry.getHeader("Idempotency-Key"))
        }
    }

    @Test fun planningAndLegacyAccountCreationUseTheSameGlobalMutexWithoutNestedDeadlock() = runBlocking {
        val fixture = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.use {
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/v1/accounts" -> {
                        entered.countDown()
                        if (!release.await(5, TimeUnit.SECONDS)) MockResponse().setResponseCode(504)
                        else json("""{"id":"$account","name":"Cash","currency":"KZT","opening_balance_minor":"0","balance_minor":"0","created_at":"2026-10-17T00:00:00Z","version":1}""")
                    }
                    "/api/v1/planning/settings" -> json(ack)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            try {
                coroutineScope {
                    val legacy = async { fixture.repo.createAccount(CreateAccountRequest("Cash", "KZT", "0")) }
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    val planning = async { fixture.repo.mutateLedger("editplanningsettings", null, JsonParser.parseString(settings).asJsonObject) }
                    assertNull(withTimeoutOrNull(150) { planning.await() })
                    assertEquals(1, fixture.server.requestCount)
                    release.countDown()
                    withTimeout(5_000) { legacy.await(); planning.await() }
                    assertEquals(2, fixture.server.requestCount)
                    assertNull(fixture.repo.ledgerPending.value)
                }
            } finally { release.countDown() }
        }
    }

    @Test fun stalePlanningReadCannotRepopulateCacheAfterAcknowledgedLedgerWrite() = runBlocking {
        val fixture = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.use {
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/v1/planning" -> {
                        entered.countDown(); release.await(5, TimeUnit.SECONDS)
                        json(report())
                    }
                    "/api/v1/planning/forecast" -> json(forecast())
                    "/api/v1/planning/settings" -> json(ack)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            try {
                supervisorScope {
                    val read = async { runCatching { fixture.repo.planning("2026-10") } }
                    assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                    fixture.repo.mutateLedger("editplanningsettings", null, JsonParser.parseString(settings).asJsonObject)
                    release.countDown()
                    val failure = withTimeout(5_000) { read.await().exceptionOrNull() }
                    assertTrue(failure is IllegalStateException)
                    assertNull(fixture.repo.cachedPlanning("2026-10"))
                }
            } finally { release.countDown() }
        }
    }

    @Test fun definitivePlanningConflictReleasesKeyButUnauthorizedPreservesOwnerRecovery() = runBlocking {
        val fixture = Fixture()
        fixture.use {
            fixture.server.enqueue(json("""{"error":{"code":"version_conflict","message":"Changed"}}""").setResponseCode(409))
            try { fixture.repo.mutateLedger("editplanningsettings", null, JsonParser.parseString(settings).asJsonObject); fail("Expected conflict") }
            catch (_: HttpException) { }
            assertNull(fixture.recovery.pending); assertNull(fixture.repo.ledgerPending.value)
            fixture.server.enqueue(json("""{"error":{"code":"unauthorized","message":"Login"}}""").setResponseCode(401))
            try { fixture.repo.mutateLedger("editplanningsettings", null, JsonParser.parseString(settings).asJsonObject); fail("Expected unauthorized") }
            catch (_: HttpException) { }
            val ownerPending = requireNotNull(fixture.recovery.pending)
            fixture.repo.expireSession(fixture.repo.session.value)
            assertNull(fixture.repo.ledgerPending.value); assertEquals(ownerPending, fixture.recovery.pending)
        }
    }

    @Test fun idempotencyConflictCannotDiscardAnUncertainPreviouslyAcceptedKey() = runBlocking {
        val fixture = Fixture()
        fixture.use {
            fixture.server.enqueue(json("""{"error":{"code":"unavailable","message":"Retry"}}""").setResponseCode(503))
            try { fixture.repo.mutateLedger("editplanningsettings", null, JsonParser.parseString(settings).asJsonObject); fail("Expected uncertain failure") }
            catch (_: HttpException) { }
            val original = requireNotNull(fixture.recovery.pending)
            fixture.server.enqueue(json("""{"error":{"code":"idempotency_conflict","message":"Key was accepted for another payload"}}""").setResponseCode(409))
            try { fixture.repo.retryLedgerMutation(); fail("Expected key conflict") } catch (_: HttpException) { }
            assertEquals(original, fixture.recovery.pending); assertEquals(original, fixture.repo.ledgerPending.value)
            try { fixture.repo.createAccount(CreateAccountRequest("Cash", "KZT", "0")); fail("Uncertain key must still block new writes") }
            catch (_: IllegalArgumentException) { }
            assertEquals(2, fixture.server.requestCount)
        }
    }

    @Test fun changingOrRefreshingAppBaseInvalidatesPlanningDefaultsWithoutReinterpretingSettings() = runBlocking {
        val fixture = Fixture()
        fixture.use {
            val dao = fixture.db.dao()
            dao.putPreferences(FxPreferencesEntity(user, "KZT", false))
            dao.putPlanningSnapshot(PlanningSnapshotEntity(user, "2026-10", "", "old-kzt-default", "2026-10-17T00:00:00Z"))
            fixture.server.enqueue(json("""{"base_currency":"USD"}"""))
            fixture.repo.setBaseCurrency("USD")
            assertNull(dao.planningSnapshot(user, "2026-10", ""))
            assertEquals("USD", dao.preferences(user)?.baseCurrency)
            dao.putPlanningSnapshot(PlanningSnapshotEntity(user, "2026-10", "", "old-usd-default", "2026-10-17T00:00:00Z"))
            fixture.server.enqueue(json("""{"base_currency":"EUR"}"""))
            fixture.repo.refreshPreferences()
            assertNull(dao.planningSnapshot(user, "2026-10", ""))
            assertEquals("EUR", dao.preferences(user)?.baseCurrency)
            val writes = listOf(fixture.server.takeRequest(1, TimeUnit.SECONDS)!!, fixture.server.takeRequest(1, TimeUnit.SECONDS)!!)
            assertEquals(listOf("/api/v1/preferences", "/api/v1/preferences"), writes.map { it.path })
            assertNull(fixture.recovery.pending) // No settings write and no reserve-unit rewrite.
        }
    }

    private inner class Fixture : AutoCloseable {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            private val prefix = "planning_repository_${UUID.randomUUID()}_"
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
        val store = SessionStore(context)
        val recovery = Recovery()
        val server = MockWebServer().apply { start() }
        init { store.writeSession(Session("planning-owner", expires, User(user, "planning@example.com"))) }
        fun newRepository() = MoneyFlowRepository(db, store, ledgerRecovery = recovery) { ApiFactory.create(server.url("/").toString()) { null } }
        val repo = newRepository()
        override fun close() { store.clearSession(); db.close(); server.close() }
    }
    private fun report() = """{"today":"2026-10-17","timezone":"UTC+05:00","from":"2026-10-01","to":"2026-10-31","overdue_from":"2025-10-16","timeline_truncated":false,"overdue_count":0,"budgets":[],"schedules":[],"occurrences":[],"settings":{"version":0,"reserve_minor":"0","account_ids":[],"base_currency":"KZT"}}"""
    private fun forecast() = """{"as_of":"2026-10-17","until":"2026-10-31","horizon_reason":"month_end","base_currency":"KZT","complete":true,"stale":false,"fetched_at":null,"missing_currencies":[],"accounts":[],"obligations":[],"balance_minor":"0","reserve_minor":"0","mandatory_minor":"0","free_minor":"0","per_day_minor":"0","days":15,"overdue_from":"2025-10-16","timeline_truncated":false}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
