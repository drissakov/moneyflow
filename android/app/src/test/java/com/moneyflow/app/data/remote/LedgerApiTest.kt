package com.moneyflow.app.data.remote

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class LedgerApiTest {
    private val id = "a1111111-1111-4111-8111-111111111111"
    private val user = "b1111111-1111-4111-8111-111111111111"
    private val key = "c1111111-1111-4111-8111-111111111111"

    @Test fun historyAcrossAllAccountsUsesOpaqueCursorAndCapturedSession() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(json("""{"transactions":[],"next_cursor":"opaque-next"}"""))
            val api = ApiFactory.create(server.url("/").toString()) { "newer-session" }
            val result = api.history(cursor = "opaque==cursor", query = "coffee", from = "2026-01-01T00:00:00Z",
                includeDeleted = true, authorization = "Bearer captured-session")
            assertEquals("opaque-next", result.nextCursor)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            val url = request.requestUrl!!
            assertNull(url.queryParameter("account_id")); assertEquals("opaque==cursor", url.queryParameter("cursor"))
            assertEquals("coffee", url.queryParameter("q")); assertEquals("true", url.queryParameter("include_deleted"))
            assertEquals("Bearer captured-session", request.getHeader("Authorization"))
        }
    }

    @Test fun deleteRetryPreservesExactBodyAndUuidAcrossSerialization() = runBlocking {
        MockWebServer().use { server ->
            server.start(); repeat(2) { server.enqueue(json("{}")) }
            val api = ApiFactory.create(server.url("/").toString()) { null }
            val pending = PendingLedgerMutation(user, key, "deletetransaction", id, """{"expected_version":17}""")
            val restored = Gson().fromJson(Gson().toJson(pending), PendingLedgerMutation::class.java)
            restored.validate(); assertEquals(pending, restored)
            repeat(2) { api.ledgerDelete(restored.path, restored.body.toRequestBody("application/json; charset=utf-8".toMediaType()), restored.key, "Bearer original") }
            val first = server.takeRequest(1, TimeUnit.SECONDS)!!; val second = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("DELETE", first.method); assertEquals("/api/v1/transactions/$id", first.path)
            assertEquals(pending.body, first.body.readUtf8()); assertEquals(pending.body, second.body.readUtf8())
            assertEquals(key, first.getHeader("Idempotency-Key")); assertEquals(key, second.getHeader("Idempotency-Key"))
        }
    }

    @Test fun oldServerModelsReceiveSafeDefaultsRatherThanGsonZeroes() {
        val account = Gson().fromJson("""{"id":"$id","name":"Cash","currency":"KZT","opening_balance_minor":"0","balance_minor":"0","created_at":"2026-01-01T00:00:00Z"}""", AccountDto::class.java).toDomain()
        assertEquals(1L, account.version); assertEquals("card", account.type); assertTrue(account.includeInBalance); assertFalse(account.archived)
        val tx = Gson().fromJson("""{"id":"$id","account_id":"$user","kind":"expense","amount_minor":"100","currency":"KZT","note":"Coffee","occurred_at":"2026-01-01T00:00:00Z","created_at":"2026-01-01T00:00:00Z"}""", TransactionDto::class.java).toDomain()
        assertEquals(1L, tx.version); assertEquals("manual", tx.source); assertNull(tx.deletedAt); assertNull(tx.transferId)
    }

    @Test fun pendingRestoreRequiresEntityVersionAndRoutesToRestoreEndpoint() {
        val pending = PendingLedgerMutation(user, key, "restoretransfer", id, """{"expected_version":2}""")
        pending.validate(); assertEquals("POST", pending.method); assertEquals("api/v1/transfers/$id/restore", pending.path)
        assertThrows(IllegalArgumentException::class.java) { pending.copy(body = """{"expected_version":0}""").validate() }
        assertThrows(IllegalArgumentException::class.java) { pending.copy(operation = "arbitrary").validate() }
    }

    @Test fun dateFilterUsesInclusiveStartExclusiveEndAndRejectsReversedPeriod() {
        LedgerFilter(from = "2026-01-01T00:00:00Z", to = "2026-02-01T00:00:00Z").validate()
        assertThrows(IllegalArgumentException::class.java) { LedgerFilter(from = "2026-02-01T00:00:00Z", to = "2026-01-01T00:00:00Z").validate() }
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
