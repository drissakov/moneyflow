package com.moneyflow.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AdvancedAnalyticsApiTest {
    private val account = "a7777777-7777-4777-8777-777777777777"
    private val category = "b7777777-7777-4777-8777-777777777777"

    @Test fun dashboardQueryContainsOriginalCurrencyAndCapturedOwnerTokenWithoutFxOrTimezoneParameters() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(json("{}"))
            val api = ApiFactory.create(server.url("/").toString()) { "replacement-session" }
            api.analyticsDashboard("2026-10", "KWD", 12, account, "Bearer captured-session")
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!; val url = request.requestUrl!!
            assertEquals("/api/v1/analytics/dashboard", url.encodedPath)
            assertEquals("2026-10", url.queryParameter("month")); assertEquals("KWD", url.queryParameter("currency"))
            assertEquals("12", url.queryParameter("months")); assertEquals(account, url.queryParameter("account_id"))
            assertNull(url.queryParameter("timezone")); assertEquals("Bearer captured-session", request.getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun drillDownTransmitsExactScopeCurrencyAndReportExclusionsAlongsideOpaqueCursor() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(json("""{"transactions":[],"next_cursor":null}"""))
            val api = ApiFactory.create(server.url("/").toString()) { null }
            api.history(accountId = account, cursor = "opaque==", from = "2026-09-30T19:00:00Z", to = "2026-10-31T19:00:00Z",
                categoryId = category, kind = "expense", authorization = "Bearer captured", currency = "KZT", analyticsOnly = true, categoryExact = true)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!; val url = request.requestUrl!!
            assertEquals("/api/v1/transactions", url.encodedPath); assertEquals("opaque==", url.queryParameter("cursor"))
            assertEquals(account, url.queryParameter("account_id")); assertEquals("KZT", url.queryParameter("currency"))
            assertEquals("expense", url.queryParameter("kind")); assertEquals(category, url.queryParameter("category_id"))
            assertEquals("true", url.queryParameter("analytics_only")); assertEquals("true", url.queryParameter("category_exact"))
            assertEquals("false", url.queryParameter("include_deleted")); assertEquals("Bearer captured", request.getHeader("Authorization"))
        }
    }

    @Test fun uncategorizedDrillDownSendsAnExplicitBooleanWithNoCategoryId() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(json("""{"transactions":[],"next_cursor":null}"""))
            ApiFactory.create(server.url("/").toString()) { null }.history(authorization = "Bearer captured", currency = "KZT", analyticsOnly = true, uncategorized = true)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!; val url = request.requestUrl!!
            assertEquals("true", url.queryParameter("uncategorized")); assertNull(url.queryParameter("category_id")); assertNull(url.queryParameter("account_id"))
        }
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
