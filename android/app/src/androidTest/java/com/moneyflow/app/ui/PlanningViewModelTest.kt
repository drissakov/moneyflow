package com.moneyflow.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.moneyflow.app.data.*
import com.moneyflow.app.data.domain.PlanningCalendar
import com.moneyflow.app.data.domain.User
import com.moneyflow.app.data.local.MoneyFlowDatabase
import com.moneyflow.app.data.remote.ApiFactory
import com.moneyflow.app.data.remote.PendingLedgerMutation
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PlanningViewModelTest {
    @Test fun frozenSettingsRevisionIsSentAndConflictRemainsVisibleAfterSuccessfulReload() = runBlocking {
        val fixture = Fixture(409)
        fixture.use {
            fixture.awaitReady()
            val original = requireNotNull(fixture.vm.state.value.report).settings
            fixture.serverVersion.set(1)
            fixture.main { fixture.vm.refresh() }
            withTimeout(10_000) { while (fixture.vm.state.value.report?.settings?.version != 1L || fixture.vm.state.value.loading) delay(10) }
            val reads = fixture.reads.get()
            fixture.main { fixture.vm.saveSettings(original, 500, emptyList(), "KZT") }
            withTimeout(10_000) { while (fixture.reads.get() <= reads || fixture.vm.state.value.busy || fixture.vm.state.value.loading || fixture.vm.state.value.error == null) delay(10) }
            assertEquals(0L, requireNotNull(fixture.sent.get())["expected_version"].asLong)
            assertTrue(requireNotNull(fixture.vm.state.value.error).contains("Данные изменились"))
            assertEquals(0L, fixture.vm.state.value.revision)
            assertNull(fixture.repo.ledgerPending.value); assertNull(fixture.recovery.pending)
            assertEquals(1L, fixture.vm.state.value.report?.settings?.version)
        }
    }

    @Test fun unauthorizedPlanningMutationExpiresCapturedSessionAndRetainsEncryptedRetryInput() = runBlocking {
        val fixture = Fixture(401)
        fixture.use {
            fixture.awaitReady()
            val original = requireNotNull(fixture.vm.state.value.report).settings
            fixture.main { fixture.vm.saveSettings(original, 700, emptyList(), "KZT") }
            withTimeout(10_000) { while (fixture.repo.session.value != null || fixture.vm.state.value.session != null) delay(10) }
            assertNull(fixture.store.readSession()); assertNull(fixture.repo.ledgerPending.value)
            val pending = requireNotNull(fixture.recovery.pending)
            assertEquals("editplanningsettings", pending.operation)
            assertEquals("700", pending.json()["reserve_minor"].asString)
            assertEquals("KZT", pending.json()["base_currency"].asString)
            assertEquals(1, fixture.writes.get()) // Expiring a session never replays automatically.
        }
    }

    private class Recovery : LedgerRecoveryStorage {
        @Volatile var pending: PendingLedgerMutation? = null
        override fun read(userId: String): PendingLedgerMutation? = pending?.takeIf { it.userId == userId }
        override fun write(action: PendingLedgerMutation) { check(pending == null || pending == action); pending = action }
        override fun clear(userId: String, expectedKey: String) { if (pending?.userId == userId && pending?.key == expectedKey) pending = null }
    }
    private inner class Fixture(private val status: Int) : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val user = "a9999999-9999-4999-8999-999999999999"
        private val account = "b9999999-9999-4999-8999-999999999999"
        private val context = object : ContextWrapper(instrumentation.targetContext) {
            private val prefix = "planning_vm_${UUID.randomUUID()}_"
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        private val database = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
        val store = SessionStore(context)
        val recovery = Recovery()
        val serverVersion = AtomicInteger()
        val reads = AtomicInteger(); val writes = AtomicInteger()
        val sent = AtomicReference<com.google.gson.JsonObject>()
        private val models = ViewModelStore()
        private val server = MockWebServer()
        val repo: MoneyFlowRepository
        lateinit var vm: PlanningViewModel
        init {
            store.writeSession(Session("planning-vm-owner", Instant.now().plusSeconds(3600).toString(), User(user, "planning-vm@example.com")))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "PUT") {
                        writes.incrementAndGet(); sent.set(JsonParser.parseString(request.body.readUtf8()).asJsonObject)
                        return json("""{"error":{"code":"${if (status == 401) "unauthorized" else "version_conflict"}","message":"Rejected"}}""").setResponseCode(status)
                    }
                    return when (request.requestUrl?.encodedPath) {
                        "/api/v1/accounts" -> json("""{"accounts":[{"id":"$account","name":"Cash","currency":"KZT","opening_balance_minor":"0","balance_minor":"0","created_at":"2026-01-01T00:00:00Z","type":"cash","version":1}]}""")
                        "/api/v1/categories" -> json("""{"categories":[]}""")
                        "/api/v1/planning" -> {
                            reads.incrementAndGet()
                            json(report(requireNotNull(request.requestUrl?.queryParameter("from")), requireNotNull(request.requestUrl?.queryParameter("to"))))
                        }
                        "/api/v1/planning/forecast" -> json(forecast())
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            repo = MoneyFlowRepository(database, store, ledgerRecovery = recovery) { ApiFactory.create(server.url("/").toString()) { null } }
            main { vm = ViewModelProvider(models, PlanningViewModel.Factory(context, repo))[PlanningViewModel::class.java] }
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        suspend fun awaitReady() { withTimeout(10_000) { while (!vm.state.value.canWrite || vm.state.value.accounts.isEmpty() || vm.state.value.loading) delay(10) } }
        private fun report(from: String, to: String): String {
            val today = PlanningCalendar.today()
            return """{"today":"$today","timezone":"UTC+05:00","from":"$from","to":"$to","overdue_from":"${today.minusDays(366)}","timeline_truncated":false,"overdue_count":0,"budgets":[],"schedules":[],"occurrences":[],"settings":{"version":${serverVersion.get()},"reserve_minor":"0","account_ids":[],"base_currency":"KZT"}}"""
        }
        private fun forecast(): String {
            val today = PlanningCalendar.today(); val end = YearMonth.from(today).atEndOfMonth(); val days = ChronoUnit.DAYS.between(today, end) + 1
            return """{"as_of":"$today","until":"$end","horizon_reason":"month_end","base_currency":"KZT","complete":true,"stale":false,"fetched_at":null,"missing_currencies":[],"accounts":[],"obligations":[],"balance_minor":"0","reserve_minor":"0","mandatory_minor":"0","free_minor":"0","per_day_minor":"0","days":$days,"overdue_from":"${today.minusDays(366)}","timeline_truncated":false}"""
        }
        override fun close() { main { models.clear() }; store.clearSession(); database.close(); server.close() }
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
