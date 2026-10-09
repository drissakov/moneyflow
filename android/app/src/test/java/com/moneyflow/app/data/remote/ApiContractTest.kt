package com.moneyflow.app.data.remote

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ApiContractTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    @Test fun `large balances are decoded from strings without floating point`() = runBlocking {
        server.enqueue(json("""{"accounts":[{"id":"f7d784a7-8055-4135-9459-237f8b11002d","name":"Savings","currency":"USD","opening_balance_minor":"0","balance_minor":"9223372036854775807","created_at":"2026-10-09T00:00:00Z"}]}"""))
        val api = ApiFactory.create(server.url("/").toString()) { "test-session" }
        val account = api.accounts().accounts.single().toDomain()
        assertEquals(Long.MAX_VALUE, account.balanceMinor)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/accounts", request.path)
        assertEquals("Bearer test-session", request.getHeader("Authorization"))
    }

    @Test fun `transaction retries preserve the key and complete serialized body`() = runBlocking {
        val response = """{"id":"t1","account_id":"a1","kind":"expense","amount_minor":"1299","currency":"USD","note":"Lunch","occurred_at":"2026-10-09T00:00:00Z","created_at":"2026-10-09T00:00:00Z"}"""
        server.enqueue(json(response))
        server.enqueue(json(response))
        val api = ApiFactory.create(server.url("/").toString()) { null }
        val body = CreateTransactionRequest("a1", "expense", "1299", "Lunch", "2026-10-09T00:00:00Z")
        val key = "efcb39ec-926c-4cfb-b25f-b0467c1051b7"
        api.createTransaction(key, body)
        api.createTransaction(key, body)
        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        val second = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/transactions", first.path)
        assertEquals(key, first.getHeader("Idempotency-Key"))
        assertEquals(key, second.getHeader("Idempotency-Key"))
        val serialized = first.body.readUtf8()
        assertEquals(serialized, second.body.readUtf8())
        val json = JsonParser.parseString(serialized).asJsonObject
        assertTrue(json["amount_minor"].asJsonPrimitive.isString)
        assertEquals("1299", json["amount_minor"].asString)
        assertEquals(body.occurredAt, json["occurred_at"].asString)
    }

    @Test fun `signout revokes the captured token after the active session is already removed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val api = ApiFactory.create(server.url("/").toString()) { "a-different-active-session" }
        api.logout("Bearer captured-signed-out-session")
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/auth/logout", request.path)
        assertEquals("Bearer captured-signed-out-session", request.getHeader("Authorization"))
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
