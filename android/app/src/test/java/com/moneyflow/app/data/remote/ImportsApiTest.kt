package com.moneyflow.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class ImportsApiTest {
    private val id = "74b729ee-1188-419c-9b47-8c39e63181d4"

    @Test fun `lost commit response retries exactly the same key body and captured authorization`() = runBlocking {
        // Pin both ends to IPv4: CI's localhost DNS may prefer ::1 while the server binds IPv4.
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val baseUrl = "http://127.0.0.1:${server.port}/"
            val api = ApiFactory.createImports(baseUrl) { "different-current-user" }
            val request = ImportCommitRequest(listOf(ImportSelectionRow(1, null, false, true)))
            val key = "74b729ee-1188-419c-9b47-8c39e63181d6"
            try { api.commit(id, request, key, "Bearer captured-user"); fail("Expected response loss") } catch (_: IOException) { }
            // Prove the entire first POST reached the server; connection refusal is not response loss.
            val first = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "The server did not receive the original commit" }
            server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(
                """{"id":"$id","account_id":"74b729ee-1188-419c-9b47-8c39e63181d5","bank":"kaspi","status":"committed","imported_count":1,"skipped_count":1,"balance_minor":"-100","created_at":"2026-01-01T00:00:00Z","committed_at":"2026-01-01T00:00:01Z"}"""))
            // A new client models reopening the app with its saved confirmation, without pooled-socket races.
            val resumedApi = ApiFactory.createImports(baseUrl) { "different-current-user" }
            resumedApi.commit(id, request, key, "Bearer captured-user").validated()
            val second = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "The server did not receive the replay" }
            assertEquals("/api/v1/imports/$id/commit", first.path)
            assertEquals(key, first.getHeader("Idempotency-Key")); assertEquals(key, second.getHeader("Idempotency-Key"))
            assertEquals("Bearer captured-user", first.getHeader("Authorization"))
            assertEquals("Bearer captured-user", second.getHeader("Authorization"))
            assertEquals(first.body.readUtf8(), second.body.readUtf8())
            assertEquals(2, server.requestCount) // Neither client may silently retry a financial POST.
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
