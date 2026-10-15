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
import com.moneyflow.app.data.local.TransactionEntity
import com.moneyflow.app.data.remote.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import java.time.Instant
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LedgerRepositoryTest {
    private val userId = "a2222222-2222-4222-8222-222222222222"
    private val accountId = "b2222222-2222-4222-8222-222222222222"
    private val txId = "c2222222-2222-4222-8222-222222222222"
    private class Storage : LedgerRecoveryStorage {
        var pending: PendingLedgerMutation? = null
        override fun read(userId: String): PendingLedgerMutation? = pending?.takeIf { it.userId == userId }
        override fun write(action: PendingLedgerMutation) { check(pending == null || pending == action); pending = action }
        override fun clear(userId: String, expectedKey: String) { if (pending?.userId == userId && pending?.key == expectedKey) pending = null }
    }

    @Test fun uncertainMutationSurvivesRepositoryRestartAndStaleReplayCannotResurrectCache() = runBlocking {
        val context = isolatedContext()
        val db = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
        val store = SessionStore(context)
        store.clearPending(userId)
        store.writeSession(Session("ledger-recovery-test", Instant.now().plusSeconds(3600).toString(), User(userId, "ledger@example.com")))
        val recovery = Storage()
        MockWebServer().use { server ->
            server.start()
            try {
                val api = ApiFactory.create(server.url("/").toString()) { null }
                val repository = MoneyFlowRepository(db, store, ledgerRecovery = recovery) { api }
                val body = JsonParser.parseString("""{"expected_version":1,"account_id":"$accountId","kind":"expense","amount_minor":"200","note":"Changed","occurred_at":"2026-01-01T00:00:00Z","category_id":null,"exclude_from_analytics":false}""").asJsonObject
                server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"unavailable","message":"try again"}}"""))
                try { repository.mutateLedger("edittransaction", txId, body); fail("Expected HTTP503") } catch (_: HttpException) { }
                val saved = requireNotNull(recovery.pending)
                assertEquals(saved, repository.ledgerPending.value)
                // On another device the edited record was later deleted. The old idempotency receipt remains active.
                db.dao().putTransactions(listOf(TransactionEntity(userId, txId, accountId, "expense", 200, "KZT", "Changed",
                    "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", version = 3, deletedAt = "2026-01-03T00:00:00Z")))
                val restarted = MoneyFlowRepository(db, store, ledgerRecovery = recovery) { api }
                assertEquals(saved, restarted.ledgerPending.value)
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":"$txId","account_id":"$accountId","kind":"expense","amount_minor":"200","currency":"KZT","note":"Changed","occurred_at":"2026-01-01T00:00:00Z","created_at":"2026-01-01T00:00:00Z","version":2,"source":"manual","deleted_at":null}"""))
                restarted.retryLedgerMutation()
                assertNull(recovery.pending); assertNull(restarted.ledgerPending.value)
                assertTrue(db.dao().observeTransactions(userId, accountId).first().isEmpty())
                val first = server.takeRequest(1, TimeUnit.SECONDS)!!; val retry = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals(saved.key, first.getHeader("Idempotency-Key")); assertEquals(saved.key, retry.getHeader("Idempotency-Key"))
                assertEquals(saved.body, first.body.readUtf8()); assertEquals(saved.body, retry.body.readUtf8())
                assertEquals("Bearer ledger-recovery-test", retry.getHeader("Authorization"))
            } finally { store.clearSession(); db.close() }
        }
    }

    @Test fun pendingMutationStaysWithItsOwnerAcrossLogoutAndDifferentUserLogin() = runBlocking {
        val context = isolatedContext()
        val db = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
        val store = SessionStore(context)
        val expires = Instant.now().plusSeconds(3600).toString()
        store.clearPending(userId)
        store.writeSession(Session("owner-a-token", expires, User(userId, "a@example.com")))
        val saved = PendingLedgerMutation(userId, "e2222222-2222-4222-8222-222222222222", "deletetransaction", txId,
            "{\"expected_version\":1}")
        val recovery = Storage().apply { pending = saved }
        val otherId = "f2222222-2222-4222-8222-222222222222"
        store.clearPending(otherId)
        MockWebServer().use { server ->
            server.start()
            try {
                val api = ApiFactory.create(server.url("/").toString()) { null }
                val repository = MoneyFlowRepository(db, store, ledgerRecovery = recovery) { api }
                assertEquals(saved, repository.ledgerPending.value)
                val previousSession = requireNotNull(repository.session.value)
                repository.expireSession()
                assertNull(repository.ledgerPending.value)
                assertEquals(saved, recovery.pending)
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"access_token":"owner-b-token","expires_at":"$expires","user":{"id":"$otherId","email":"b@example.com"}}"""))
                repository.authenticate("b@example.com", "long-test-password", false)
                repository.expireSession(previousSession) // A delayed 401 from owner A must leave B signed in.
                assertEquals(otherId, repository.session.value?.user?.id)
                assertNull(repository.ledgerPending.value)
                try { repository.retryLedgerMutation(); fail("Another user must not retry owner's pending mutation") }
                catch (_: IllegalStateException) { }
                assertEquals(1, server.requestCount)
                repository.expireSession()
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"access_token":"owner-a-new-token","expires_at":"$expires","user":{"id":"$userId","email":"a@example.com"}}"""))
                repository.authenticate("a@example.com", "long-test-password", false)
                assertEquals(saved, repository.ledgerPending.value)
                assertEquals(saved, recovery.pending)
                assertEquals(2, server.requestCount) // Login requests only; never replay automatically.
            } finally { store.clearSession(); db.close() }
        }
    }

    @Test fun pendingManualWriteBlocksOtherMutationsBeforeNetworkRequest() = runBlocking {
        val context = isolatedContext()
        val db = Room.inMemoryDatabaseBuilder(context, MoneyFlowDatabase::class.java).build()
        val store = SessionStore(context)
        store.writeSession(Session("ledger-mutual-test", Instant.now().plusSeconds(3600).toString(), User(userId, "ledger@example.com")))
        val pending = PendingTransaction(userId, "d2222222-2222-4222-8222-222222222222",
            CreateTransactionRequest(accountId, "expense", "100", "", "2026-01-01T00:00:00Z"))
        store.writePending(pending)
        MockWebServer().use { server ->
            server.start()
            try {
                val api = ApiFactory.create(server.url("/").toString()) { null }
                val repository = MoneyFlowRepository(db, store, ledgerRecovery = Storage()) { api }
                try {
                    repository.mutateLedger("deletetransaction", txId, JsonParser.parseString("""{"expected_version":1}""").asJsonObject)
                    fail("Pending manual write must block ledger mutation")
                } catch (_: IllegalArgumentException) { }
                assertEquals(0, server.requestCount); assertNull(repository.ledgerPending.value)
            } finally { store.clearPending(userId); store.clearSession(); db.close() }
        }
    }
    private fun isolatedContext(): Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("ledger_repository_test_$name", mode)
    }

}
