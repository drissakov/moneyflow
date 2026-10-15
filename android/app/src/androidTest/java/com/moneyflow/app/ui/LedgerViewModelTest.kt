package com.moneyflow.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moneyflow.app.data.LedgerRecoveryStorage
import com.moneyflow.app.data.remote.PendingLedgerMutation
import com.moneyflow.app.data.domain.MoneyTransaction
import com.moneyflow.app.data.MoneyFlowRepository
import com.moneyflow.app.data.Session
import com.moneyflow.app.data.SessionStore
import com.moneyflow.app.data.domain.User
import com.moneyflow.app.data.local.MoneyFlowDatabase
import com.moneyflow.app.data.remote.ApiFactory
import com.moneyflow.app.data.remote.LedgerFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LedgerViewModelTest {
    @Test fun explicitRefreshFetchesMetadataButSearchOnlyLoadsHistory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("ledger_viewmodel_test_$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
        val store = SessionStore(context)
        val user = "a4444444-4444-4444-8444-444444444444"
        val account = "b4444444-4444-4444-8444-444444444444"
        store.clearPending(user)
        store.writeSession(Session("ledger-vm-test", Instant.now().plusSeconds(3600).toString(), User(user, "vm@example.com")))
        val accountCalls = AtomicInteger(); val categoryCalls = AtomicInteger(); val historyCalls = AtomicInteger()
        val name = AtomicReference("Original")
        val models = ViewModelStore()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when (request.requestUrl?.encodedPath) {
                        "/api/v1/accounts" -> {
                            accountCalls.incrementAndGet()
                            """{"accounts":[{"id":"$account","name":"${name.get()}","currency":"KZT","opening_balance_minor":"0","balance_minor":"0","created_at":"2026-01-01T00:00:00Z","version":1}]}"""
                        }
                        "/api/v1/categories" -> { categoryCalls.incrementAndGet(); """{"categories":[]}""" }
                        "/api/v1/transactions" -> { historyCalls.incrementAndGet(); """{"transactions":[],"next_cursor":null}""" }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            server.start()
            try {
                val repository = MoneyFlowRepository(db, store) { ApiFactory.create(server.url("/").toString()) { null } }
                lateinit var viewModel: LedgerViewModel
                instrumentation.runOnMainSync { viewModel = ViewModelProvider(models, LedgerViewModel.Factory(context, repository))[LedgerViewModel::class.java] }
                withTimeout(10_000) { while (viewModel.state.value.accounts.firstOrNull()?.name != "Original" || viewModel.state.value.loading || categoryCalls.get() != 1) delay(10) }
                assertEquals(1, accountCalls.get()); assertEquals(1, categoryCalls.get())
                name.set("Renamed elsewhere")
                instrumentation.runOnMainSync { viewModel.refresh() }
                withTimeout(10_000) { while (viewModel.state.value.accounts.firstOrNull()?.name != "Renamed elsewhere" || categoryCalls.get() != 2 || viewModel.state.value.loading) delay(10) }
                assertEquals(2, accountCalls.get()); assertEquals(2, categoryCalls.get())
                val beforeSearch = historyCalls.get()
                instrumentation.runOnMainSync { viewModel.setFilter(LedgerFilter(query = "Coffee")) }
                withTimeout(10_000) { while (historyCalls.get() == beforeSearch || viewModel.state.value.loading) delay(10) }
                assertEquals(2, accountCalls.get()); assertEquals(2, categoryCalls.get())
                assertNull(viewModel.state.value.error)
            } finally {
                instrumentation.runOnMainSync { models.clear() }
                store.clearSession(); db.close()
            }
        }
    }
    @Test fun unauthorizedMutationReturnsToSignInAndPreservesExactPendingRequest() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("ledger_viewmodel_401_test_$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
        val store = SessionStore(context)
        val user = "a5555555-5555-4555-8555-555555555555"
        val account = "b5555555-5555-4555-8555-555555555555"
        val tx = "c5555555-5555-4555-8555-555555555555"
        store.clearPending(user)
        store.writeSession(Session("expired-on-server", Instant.now().plusSeconds(3600).toString(), User(user, "expired@example.com")))
        var saved: PendingLedgerMutation? = null
        val recovery = object : LedgerRecoveryStorage {
            override fun read(userId: String): PendingLedgerMutation? = saved?.takeIf { it.userId == userId }
            override fun write(action: PendingLedgerMutation) { saved = action }
            override fun clear(userId: String, expectedKey: String) { if (saved?.userId == userId && saved?.key == expectedKey) saved = null }
        }
        val deletes = AtomicInteger()
        val models = ViewModelStore()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "DELETE") {
                        deletes.incrementAndGet()
                        return MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                            .setBody("""{"error":{"code":"unauthorized","message":"Sign in again"}}""")
                    }
                    val body = when (request.requestUrl?.encodedPath) {
                        "/api/v1/accounts" -> """{"accounts":[{"id":"$account","name":"Cash","currency":"KZT","opening_balance_minor":"0","balance_minor":"0","created_at":"2026-01-01T00:00:00Z"}]}"""
                        "/api/v1/categories" -> """{"categories":[]}"""
                        "/api/v1/transactions" -> """{"transactions":[],"next_cursor":null}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            server.start()
            try {
                val repository = MoneyFlowRepository(db, store, ledgerRecovery = recovery) { ApiFactory.create(server.url("/").toString()) { null } }
                lateinit var viewModel: LedgerViewModel
                instrumentation.runOnMainSync { viewModel = ViewModelProvider(models, LedgerViewModel.Factory(context, repository))[LedgerViewModel::class.java] }
                withTimeout(10_000) { while (viewModel.state.value.accounts.isEmpty() || !viewModel.state.value.canWrite || viewModel.state.value.loading) delay(10) }
                val original = MoneyTransaction(tx, account, "expense", 100, "KZT", "Coffee", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
                instrumentation.runOnMainSync { viewModel.deleteTransaction(original) }
                withTimeout(10_000) { while (repository.session.value != null || viewModel.state.value.session != null) delay(10) }
                assertNull(store.readSession()); assertNull(repository.ledgerPending.value)
                val pending = requireNotNull(saved)
                assertEquals(user, pending.userId); assertEquals(tx, pending.entityId)
                assertEquals("deletetransaction", pending.operation); assertEquals("{\"expected_version\":1}", pending.body)
                assertEquals(1, deletes.get()) // Expiry never replays automatically.
            } finally {
                instrumentation.runOnMainSync { models.clear() }
                store.clearSession(); db.close()
            }
        }
    }

    @Test fun versionConflictRemainsVisibleAfterSuccessfulHistoryReloadAndReleasesPendingKey() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("ledger_viewmodel_409_test_$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
        val store = SessionStore(context)
        val user = "a6666666-6666-4666-8666-666666666666"
        val account = "b6666666-6666-4666-8666-666666666666"
        val tx = "c6666666-6666-4666-8666-666666666666"
        store.clearPending(user)
        store.writeSession(Session("conflict-test", Instant.now().plusSeconds(3600).toString(), User(user, "conflict@example.com")))
        var saved: PendingLedgerMutation? = null
        val recovery = object : LedgerRecoveryStorage {
            override fun read(userId: String): PendingLedgerMutation? = saved?.takeIf { it.userId == userId }
            override fun write(action: PendingLedgerMutation) { saved = action }
            override fun clear(userId: String, expectedKey: String) { if (saved?.userId == userId && saved?.key == expectedKey) saved = null }
        }
        val histories = AtomicInteger()
        val deletes = AtomicInteger()
        val models = ViewModelStore()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "DELETE") {
                        deletes.incrementAndGet()
                        return MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
                            .setBody("""{"error":{"code":"version_conflict","message":"Refresh before saving"}}""")
                    }
                    val body = when (request.requestUrl?.encodedPath) {
                        "/api/v1/accounts" -> """{"accounts":[{"id":"$account","name":"Cash","currency":"KZT","opening_balance_minor":"0","balance_minor":"0","created_at":"2026-01-01T00:00:00Z"}]}"""
                        "/api/v1/categories" -> """{"categories":[]}"""
                        "/api/v1/transactions" -> { histories.incrementAndGet(); """{"transactions":[],"next_cursor":null}""" }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            server.start()
            try {
                val repository = MoneyFlowRepository(db, store, ledgerRecovery = recovery) { ApiFactory.create(server.url("/").toString()) { null } }
                lateinit var viewModel: LedgerViewModel
                instrumentation.runOnMainSync { viewModel = ViewModelProvider(models, LedgerViewModel.Factory(context, repository))[LedgerViewModel::class.java] }
                withTimeout(10_000) { while (viewModel.state.value.accounts.isEmpty() || !viewModel.state.value.canWrite || viewModel.state.value.loading) delay(10) }
                val before = histories.get()
                val original = MoneyTransaction(tx, account, "expense", 100, "KZT", "Coffee", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
                instrumentation.runOnMainSync { viewModel.deleteTransaction(original) }
                withTimeout(10_000) { while (deletes.get() != 1 || histories.get() <= before || viewModel.state.value.busy || viewModel.state.value.loading) delay(10) }
                assertTrue(viewModel.state.value.error?.contains("Данные изменились") == true)
                assertFalse(viewModel.state.value.offline)
                assertEquals(0L, viewModel.state.value.revision) // No mutation success / no silent form close.
                assertNull(saved); assertNull(repository.ledgerPending.value); assertNull(viewModel.state.value.pending)
                assertEquals(user, repository.session.value?.user?.id)
            } finally {
                instrumentation.runOnMainSync { models.clear() }
                store.clearSession(); db.close()
            }
        }
    }

}
