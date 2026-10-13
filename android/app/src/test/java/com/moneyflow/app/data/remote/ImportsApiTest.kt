package com.moneyflow.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class ImportsApiTest {
    private val id = "74b729ee-1188-419c-9b47-8c39e63181d4"

    @Test fun `lost commit response retries exactly the same key body and captured authorization`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(
                """{"id":"$id","account_id":"74b729ee-1188-419c-9b47-8c39e63181d5","bank":"kaspi","status":"committed","imported_count":1,"skipped_count":1,"balance_minor":"-100","created_at":"2026-01-01T00:00:00Z","committed_at":"2026-01-01T00:00:01Z"}"""))
            val api = ApiFactory.createImports(server.url("/").toString()) { "different-current-user" }
            val request = ImportCommitRequest(listOf(ImportSelectionRow(1, null, false, true)))
            val key = "74b729ee-1188-419c-9b47-8c39e63181d6"
            try { api.commit(id, request, key, "Bearer captured-user"); fail("Expected response loss") } catch (_: IOException) { }
            api.commit(id, request, key, "Bearer captured-user").validated()
            val first = server.takeRequest(1, TimeUnit.SECONDS)!!; val second = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/imports/$id/commit", first.path)
            assertEquals(key, first.getHeader("Idempotency-Key")); assertEquals(key, second.getHeader("Idempotency-Key"))
            assertEquals("Bearer captured-user", second.getHeader("Authorization"))
            assertEquals(first.body.readUtf8(), second.body.readUtf8())
        } finally { server.shutdown() }
    }

    @Test fun `preview rejects dropped or silently collapsed identical statement rows`() {
        val rows = listOf(1, 2).map { ImportSourceRow(it, "2026-01-01T00:00:00+05:00", "expense", "100", "Example") }
        val request = ImportPreviewRequest("74b729ee-1188-419c-9b47-8c39e63181d5", "kaspi", "KZT", "a".repeat(64), "Asia/Qyzylorda", "0", "-200", rows)
        request.validate()
        val preview = ImportPreviewDto(id, request.accountId, "kaspi", "KZT", "draft", "2026-01-02T00:00:00Z", "0", "0", "-200",
            rows.map { ImportPreviewRow(it.rowId, "2025-12-31T19:00:00Z", it.kind, it.amountMinor, it.note, "new", 0) })
        preview.validated(request)
        assertThrows(IllegalArgumentException::class.java) { preview.copy(rows = preview.rows.take(1)).validated(request) }
        assertThrows(IllegalArgumentException::class.java) { preview.copy(rows = listOf(preview.rows[0], preview.rows[0])).validated(request) }
        assertThrows(IllegalArgumentException::class.java) { preview.copy(rows = preview.rows.map { it.copy(amountMinor = "101") }).validated(request) }
        preview.copy(rows = preview.rows.map { it.copy(duplicate = "exact", matchingCount = 0) }).validated(request)
        assertThrows(IllegalArgumentException::class.java) {
            preview.copy(rows = preview.rows.map { it.copy(duplicate = "possible", matchingCount = 0) }).validated(request)
        }
    }

    @Test fun `account creation preserves the initiating authorization after provider switches users`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"$id","name":"Example","currency":"KZT","opening_balance_minor":"0","balance_minor":"0","created_at":"2026-01-01T00:00:00Z"}"""))
            val api = ApiFactory.create(server.url("/").toString()) { "new-session" }
            api.createAccount(CreateAccountRequest("Example", "KZT", "0"), "Bearer original-session")
            assertEquals("Bearer original-session", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        } finally { server.shutdown() }
    }
}
