package com.moneyflow.app.data.remote

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class CategoryApiContractTest {
    private lateinit var server: MockWebServer
    private val id = "ee7d2d31-7369-4759-8125-f9d3967ded84"
    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    @Test fun `categorized transaction retry keeps category exact request body and captured session`() = runBlocking {
        val response = """{"id":"$id","account_id":"$id","kind":"expense","amount_minor":"125","currency":"KZT","note":"Кофе","occurred_at":"2026-10-12T00:00:00Z","created_at":"2026-10-12T00:00:00Z","category_id":"$id"}"""
        repeat(2) { server.enqueue(json(response)) }
        val api = ApiFactory.create(server.url("/").toString()) { "different-session" }
        val request = CreateTransactionRequest(id, "expense", "125", "Кофе", "2026-10-12T00:00:00Z", id)
        repeat(2) { api.createTransaction(id, request, "Bearer captured-session") }
        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        val second = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer captured-session", first.getHeader("Authorization"))
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
        val body = first.body.readUtf8()
        assertEquals(body, second.body.readUtf8())
        assertEquals(id, JsonParser.parseString(body).asJsonObject["category_id"].asString)
    }

    @Test fun `analytics requests a month currency and explicit timezone on the server`() = runBlocking {
        server.enqueue(json("""{"month":"2026-10","currency":"JPY","timezone":"Asia/Tokyo","income_minor":"0","expense_minor":"0","income":[],"expenses":[]}"""))
        val api = ApiFactory.create(server.url("/").toString()) { "different-session" }
        val analytics = api.analytics("2026-10", "JPY", "Asia/Tokyo", "Bearer captured-session")
            .toDomain("2026-10", "JPY", "Asia/Tokyo")
        assertEquals(0L, analytics.expenseMinor)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/analytics", request.requestUrl!!.encodedPath)
        assertEquals("2026-10", request.requestUrl!!.queryParameter("month"))
        assertEquals("JPY", request.requestUrl!!.queryParameter("currency"))
        assertEquals("Asia/Tokyo", request.requestUrl!!.queryParameter("timezone"))
        assertEquals("Bearer captured-session", request.getHeader("Authorization"))
    }

    @Test fun `custom subcategory creation sends its parent and income omits a parent`() = runBlocking {
        server.enqueue(json("""{"id":"$id","kind":"expense","name":"Кофе","parent_id":"ee7d2d31-7369-4759-8125-f9d3967ded85","is_default":false}"""))
        server.enqueue(json("""{"id":"$id","kind":"income","name":"Грант","parent_id":null,"is_default":false}"""))
        val api = ApiFactory.create(server.url("/").toString()) { null }
        api.createCategory(CreateCategoryRequest("expense", "Кофе", "ee7d2d31-7369-4759-8125-f9d3967ded85"), "Bearer session")
        api.createCategory(CreateCategoryRequest("income", "Грант"), "Bearer session")
        val expense = JsonParser.parseString(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).asJsonObject
        val income = JsonParser.parseString(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).asJsonObject
        assertEquals("ee7d2d31-7369-4759-8125-f9d3967ded85", expense["parent_id"].asString)
        assertFalse(income.has("parent_id"))
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
