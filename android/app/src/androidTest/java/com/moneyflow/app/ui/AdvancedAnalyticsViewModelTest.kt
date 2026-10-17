package com.moneyflow.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moneyflow.app.data.*
import com.moneyflow.app.data.domain.PlanningCalendar
import com.moneyflow.app.data.domain.User
import com.moneyflow.app.data.local.MoneyFlowDatabase
import com.moneyflow.app.data.remote.ApiFactory
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AdvancedAnalyticsViewModelTest {
    @Test fun filterGenerationWinsAgainstDelayedOldResponseAndOfflineCacheNeverCrossesScopes() = runBlocking {
        Fixture().use { fixture ->
            fixture.awaitReady()
            fixture.main { fixture.vm.setAccount(AdvancedAnalyticsTestFixtures.ACCOUNT) }
            withTimeout(10_000) { while (fixture.vm.state.value.loading || fixture.vm.state.value.report?.accountId == null) delay(10) }
            fixture.holdNext.set(true)
            val previous = YearMonth.parse(PlanningCalendar.month()).minusMonths(1).toString()
            fixture.main { fixture.vm.setMonth(previous) }
            assertTrue(withContext(Dispatchers.IO) { fixture.entered.await(5, TimeUnit.SECONDS) })
            fixture.main { fixture.vm.setCurrency("USD") }
            withTimeout(10_000) { while (fixture.vm.state.value.loading || fixture.vm.state.value.report?.currency != "USD") delay(10) }
            assertNull(fixture.vm.state.value.accountId)
            assertEquals(previous, fixture.vm.state.value.report?.month)
            fixture.release.countDown()
            assertTrue(withContext(Dispatchers.IO) { fixture.released.await(5, TimeUnit.SECONDS) })
            assertEquals("USD", fixture.vm.state.value.report?.currency)
            fixture.status.set(503)
            fixture.main { fixture.vm.refresh() }
            withTimeout(10_000) { while (fixture.vm.state.value.loading || !fixture.vm.state.value.offline) delay(10) }
            assertEquals("USD", fixture.vm.state.value.report?.currency)
            assertNotNull(fixture.vm.state.value.receivedAt)
            fixture.main { fixture.vm.setMonths(3) }
            withTimeout(10_000) { while (fixture.vm.state.value.loading) delay(10) }
            assertNull(fixture.vm.state.value.report) // Six-month cache must not masquerade as a three-month report.
            assertTrue(fixture.vm.state.value.offline)
            assertTrue(fixture.methods.all { it == "GET" })
        }
    }

    @Test fun unauthorizedReportExpiresOnlyCapturedSessionAndClearsFinancialReportState() = runBlocking {
        Fixture().use { fixture ->
            fixture.awaitReady()
            fixture.status.set(401)
            fixture.main { fixture.vm.refresh() }
            withTimeout(10_000) { while (fixture.repo.session.value != null || fixture.vm.state.value.session != null) delay(10) }
            assertNull(fixture.vm.state.value.report); assertNull(fixture.store.readSession())
            assertNull(fixture.repo.ledgerPending.value)
        }
    }

    @Test fun consolidatedModeNormalizesScopeWinsTheOldRequestRaceAndUsesOnlyItsOwnOfflineSnapshot() = runBlocking {
        Fixture().use { fixture ->
            fixture.awaitReady()
            val selected = fixture.vm.state.value.month
            fixture.main { fixture.vm.setAccount(AdvancedAnalyticsTestFixtures.ACCOUNT) }
            withTimeout(10_000) { while (fixture.vm.state.value.loading || fixture.vm.state.value.report?.accountId == null) delay(10) }
            fixture.main { fixture.vm.setCurrency("USD") }
            withTimeout(10_000) { while (fixture.vm.state.value.loading || fixture.vm.state.value.report?.currency != "USD") delay(10) }
            fixture.holdNext.set(true)
            val previous = YearMonth.parse(selected).minusMonths(1).toString()
            fixture.main { fixture.vm.setMonth(previous) }
            assertTrue(withContext(Dispatchers.IO) { fixture.entered.await(5, TimeUnit.SECONDS) })
            fixture.main { fixture.vm.setMode("consolidated") }
            withTimeout(10_000) { while (fixture.vm.state.value.loading || fixture.vm.state.value.report?.mode != "consolidated") delay(10) }
            assertEquals("KZT", fixture.vm.state.value.currency)
            assertNull(fixture.vm.state.value.accountId)
            assertEquals("consolidated", fixture.vm.state.value.filter.mode)
            assertNotNull(fixture.vm.state.value.report?.conversion)
            fixture.release.countDown()
            assertTrue(withContext(Dispatchers.IO) { fixture.released.await(5, TimeUnit.SECONDS) })
            assertEquals("consolidated", fixture.vm.state.value.report?.mode)
            fixture.status.set(503)
            fixture.main { fixture.vm.refresh() }
            withTimeout(10_000) { while (fixture.vm.state.value.loading || !fixture.vm.state.value.offline) delay(10) }
            assertEquals("consolidated", fixture.vm.state.value.report?.mode)
            assertTrue(fixture.vm.state.value.error.orEmpty().contains("Курсы валют"))
            fixture.main { fixture.vm.setMode("original") }
            withTimeout(10_000) { while (fixture.vm.state.value.loading) delay(10) }
            assertNull(fixture.vm.state.value.report) // There was never a native KZT snapshot for this prior month.
            assertEquals("original", fixture.vm.state.value.mode)
            fixture.main { fixture.vm.setMonth(selected) }
            withTimeout(10_000) { while (fixture.vm.state.value.loading) delay(10) }
            assertEquals("original", fixture.vm.state.value.report?.mode)
            assertNull(fixture.vm.state.value.report?.conversion)
            assertTrue(fixture.vm.state.value.offline)
            assertTrue(fixture.methods.all { it == "GET" })
        }
    }

    @Test fun historicalSelectionClampsFutureMonthAndOfflineValuationNeverUsesTheOtherPolicyCache() = runBlocking {
        Fixture().use { fixture ->
            fixture.awaitReady()
            val current = PlanningCalendar.month()
            val future = YearMonth.parse(current).plusMonths(1).toString()
            fixture.main { fixture.vm.setMonth(future) }
            withTimeout(10_000) { while (fixture.vm.state.value.loading || fixture.vm.state.value.report?.month != future) delay(10) }
            fixture.main { fixture.vm.setMode("consolidated") }
            withTimeout(10_000) { while (fixture.vm.state.value.loading || fixture.vm.state.value.report?.conversion?.policy != "historical") delay(10) }
            assertEquals(current, fixture.vm.state.value.month)
            assertEquals("historical", fixture.vm.state.value.valuation)
            fixture.main { fixture.vm.setMonth(future) }
            assertEquals(current, fixture.vm.state.value.month)
            assertNotNull(fixture.vm.state.value.error)
            fixture.main { fixture.vm.setValuation("latest_snapshot") }
            withTimeout(10_000) { while (fixture.vm.state.value.loading || fixture.vm.state.value.report?.conversion?.policy != "latest_snapshot") delay(10) }
            fixture.status.set(503)
            fixture.main { fixture.vm.setValuation("historical") }
            withTimeout(10_000) { while (fixture.vm.state.value.loading || !fixture.vm.state.value.offline) delay(10) }
            assertEquals("historical", fixture.vm.state.value.report?.conversion?.policy)
            fixture.main { fixture.vm.setValuation("latest_snapshot") }
            withTimeout(10_000) { while (fixture.vm.state.value.loading || !fixture.vm.state.value.offline) delay(10) }
            assertEquals("latest_snapshot", fixture.vm.state.value.report?.conversion?.policy)
        }
    }

    private inner class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val user = "a4444444-4444-4444-8444-444444444444"
        private val context = object : ContextWrapper(instrumentation.targetContext) {
            private val prefix = "advanced_analytics_vm_${UUID.randomUUID()}_"
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        private val database = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
        val store = SessionStore(context)
        val status = AtomicInteger(200)
        val holdNext = AtomicBoolean(false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val released = CountDownLatch(1)
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val models = ViewModelStore()
        private val server = MockWebServer()
        val repo: MoneyFlowRepository
        lateinit var vm: AdvancedAnalyticsViewModel
        init {
            store.writeSession(Session("analytics-vm-owner", Instant.now().plusSeconds(3600).toString(), User(user, "analytics-vm@example.com")))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    methods += request.method.orEmpty()
                    if (request.requestUrl?.encodedPath == "/api/v1/accounts") return json("""{"accounts":[{"id":"${AdvancedAnalyticsTestFixtures.ACCOUNT}","name":"Archived cash","currency":"KZT","opening_balance_minor":"1000","balance_minor":"1000","created_at":"2026-01-01T00:00:00Z","type":"cash","archived":true,"include_in_balance":false,"version":1}]}""")
                    if (request.requestUrl?.encodedPath != "/api/v1/analytics/dashboard") return MockResponse().setResponseCode(404)
                    val held = holdNext.compareAndSet(true, false)
                    if (held) { entered.countDown(); release.await(5, TimeUnit.SECONDS); released.countDown() }
                    val code = status.get()
                    if (code != 200) return json("""{"error":{"code":"${if (code == 401) "unauthorized" else "unavailable"}","message":"Unavailable"}}""").setResponseCode(code)
                    val url = requireNotNull(request.requestUrl)
                    return json(AdvancedAnalyticsTestFixtures.report(requireNotNull(url.queryParameter("month")), requireNotNull(url.queryParameter("currency")),
                        requireNotNull(url.queryParameter("months")).toInt(), url.queryParameter("account_id"),
                        mode = url.queryParameter("mode") ?: "original", valuation = url.queryParameter("valuation") ?: "historical"))
                }
            }
            server.start()
            repo = MoneyFlowRepository(database, store) { ApiFactory.create(server.url("/").toString()) { null } }
            main { vm = ViewModelProvider(models, AdvancedAnalyticsViewModel.Factory(context, repo))[AdvancedAnalyticsViewModel::class.java] }
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        suspend fun awaitReady() { withTimeout(10_000) { while (vm.state.value.loading || vm.state.value.report == null || vm.state.value.accounts.isEmpty()) delay(10) } }
        override fun close() { release.countDown(); main { models.clear() }; store.clearSession(); database.close(); server.close() }
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
