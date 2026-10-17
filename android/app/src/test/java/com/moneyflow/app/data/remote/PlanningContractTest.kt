package com.moneyflow.app.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlanningContractTest {
    private val user = "a6666666-6666-4666-8666-666666666666"
    private val id = "b6666666-6666-4666-8666-666666666666"
    private val key = "c6666666-6666-4666-8666-666666666666"

    @Test fun occurrenceRoutingKeepsDateOutsideExactRecoveryBodyAcrossRestart() {
        val body = """{"expected_template_version":7,"expected_occurrence_version":0,"amount_minor":"200","occurred_at":"2026-10-17T07:00:00Z","note":"rent","category_id":null}"""
        val request = PendingLedgerMutation(user, key, "postoccurrence", id, body, "2026-10-17")
        val restarted = Gson().fromJson(Gson().toJson(request), PendingLedgerMutation::class.java)
        restarted.validate()
        assertEquals(request, restarted); assertEquals(body, restarted.body)
        assertEquals("api/v1/schedules/$id/occurrences/2026-10-17/post", restarted.path)
        assertEquals("POST", restarted.method)
        assertFalse(restarted.json().has("due_date"))
        assertThrows(IllegalArgumentException::class.java) { restarted.copy(occurrenceDate = null).validate() }
        assertThrows(Exception::class.java) { restarted.copy(occurrenceDate = "2026-02-30").validate() }
    }

    @Test fun allPlanningRoutesAreExplicitAndUnknownOperationHasNoTransferFallback() {
        val create = PendingLedgerMutation(user, key, "createbudget", null, "{}")
        assertEquals("api/v1/budgets", create.path); assertEquals("POST", create.method)
        assertEquals("api/v1/budgets/$id", create.copy(operation = "editbudget", entityId = id).path)
        assertEquals("PUT", create.copy(operation = "editbudget", entityId = id).method)
        assertEquals("api/v1/schedules", create.copy(operation = "createschedule").path)
        assertEquals("api/v1/planning/settings", create.copy(operation = "editplanningsettings").path)
        assertEquals("PUT", create.copy(operation = "editplanningsettings").method)
        listOf("skipoccurrence" to "skip", "unskipoccurrence" to "unskip").forEach { (operation, suffix) ->
            val pending = create.copy(operation = operation, entityId = id, occurrenceDate = "2026-10-17",
                body = """{"expected_template_version":1,"expected_occurrence_version":0}""")
            pending.validate(); assertEquals("api/v1/schedules/$id/occurrences/2026-10-17/$suffix", pending.path)
        }
        val unknown = create.copy(operation = "something-transfer")
        assertThrows(IllegalArgumentException::class.java) { unknown.path }
        assertThrows(IllegalArgumentException::class.java) { unknown.method }
        assertThrows(IllegalArgumentException::class.java) { unknown.validate() }
    }

    @Test fun initialSettingsUseVersionZeroAndRejectMissingUnitsOrDuplicateAccounts() {
        val valid = PendingLedgerMutation(user, key, "editplanningsettings", null,
            """{"expected_version":0,"reserve_minor":"1500","account_ids":[],"base_currency":"KZT"}""")
        valid.validate()
        assertThrows(IllegalArgumentException::class.java) { valid.copy(body = valid.body.replace(",\"base_currency\":\"KZT\"", "")).validate() }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(body = valid.body.replace("[]", "[\"$id\",\"$id\"]")).validate() }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(body = valid.body.replace("\"1500\"", "\"-1\"")).validate() }
    }

    @Test fun forecastUsesConservativeFloorForNegativeFreeMoneyAndDoesNotCountFutureIncome() {
        val forecast = forecast(balance = "0", reserve = "5", free = "-5", daily = "-3").validated()
        assertEquals("-3", forecast.perDayMinor)
        assertThrows(IllegalArgumentException::class.java) { forecast.copy(perDayMinor = "-2").validated() }
        val income = ForecastObligationDto(id, 1, "2026-10-18", 0, "Salary", user, null, "income", "KZT", "10000", "", false,
            true, "pending", null, false, null, "10000")
        assertThrows(IllegalArgumentException::class.java) { forecast.copy(obligations = listOf(income)).validated() }
        assertThrows(IllegalArgumentException::class.java) { forecast.copy(balanceMinor = "10000", freeMinor = "9995", perDayMinor = "4997").validated() }
    }

    @Test fun missingFxAndTruncatedOverdueHistoryNeverProduceACompleteSpendableTotal() {
        val account = ForecastAccountDto(id, "USD", "USD", "1000", null)
        forecast(balance = "0", reserve = "0", free = "0", daily = "0").copy(complete = false, missingCurrencies = listOf("USD"),
            accounts = listOf(account), balanceMinor = null, mandatoryMinor = null, freeMinor = null, perDayMinor = null).validated()
        val truncated = forecast("0", "0", "0", "0").copy(complete = false, timelineTruncated = true,
            balanceMinor = null, mandatoryMinor = null, freeMinor = null, perDayMinor = null).validated()
        assertThrows(IllegalArgumentException::class.java) { truncated.copy(complete = true).validated() }
    }

    @Test fun planningReadsPassInclusiveRangeAndCapturedAuthorizationWithoutTimezoneQuestion() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(json("""{"today":"2026-10-17","timezone":"UTC+05:00","from":"2026-10-01","to":"2026-10-31","overdue_from":"2025-10-16","timeline_truncated":false,"overdue_count":0,"budgets":[],"schedules":[],"occurrences":[],"settings":{"version":0,"reserve_minor":"0","account_ids":[],"base_currency":"KZT"}}"""))
            val api = ApiFactory.create(server.url("/").toString()) { "replacement" }
            api.planning("2026-10-01", "2026-10-31", "Bearer captured").validated("2026-10-01", "2026-10-31")
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/planning", request.requestUrl!!.encodedPath)
            assertEquals("2026-10-31", request.requestUrl!!.queryParameter("to"))
            assertNull(request.requestUrl!!.queryParameter("timezone"))
            assertEquals("Bearer captured", request.getHeader("Authorization"))
        }
    }

    @Test fun mismatchedAcknowledgementCannotReleasePendingPlanningRequest() {
        val body = """{"name":"Food","category_id":"$user","currency":"KZT","limit_minor":"1000","period_start_day":1,"archived":false}"""
        val pending = PendingLedgerMutation(user, key, "createbudget", null, body)
        pending.validate()
        val response = JsonParser.parseString("""{"id":"$id","name":"Food","category_id":"$user","currency":"KZT","limit_minor":"1000","period_start_day":1,"archived":false,"version":1,"period_from":"2026-10-01","period_to":"2026-11-01","spent_minor":"0","remaining_minor":"1000","category_archived":false,"category_name":"Food"}""").asJsonObject
        validatePlanningAcknowledgement(pending, response)
        response.addProperty("currency", "USD")
        assertThrows(IllegalArgumentException::class.java) { validatePlanningAcknowledgement(pending, response) }
    }

    @Test fun postedOccurrenceAcknowledgementMustEchoActualAmountAndFrozenTemplateRevision() {
        val pending = PendingLedgerMutation(user, key, "postoccurrence", id,
            """{"expected_template_version":7,"expected_occurrence_version":2,"amount_minor":"200","occurred_at":"2026-10-17T07:00:00Z","note":"actual","category_id":null}""", "2026-10-17")
        pending.validate()
        val response = JsonParser.parseString("""{"schedule_id":"$id","schedule_version":7,"due_date":"2026-10-17","version":3,"name":"Rent","account_id":"$user","category_id":null,"kind":"expense","currency":"KZT","amount_minor":"200","note":"actual","mandatory":true,"primary_income":false,"status":"posted","transaction_id":"$key","transaction_deleted":false,"blocked_reason":null}""").asJsonObject
        validatePlanningAcknowledgement(pending, response)
        response.addProperty("amount_minor", "201")
        assertThrows(IllegalArgumentException::class.java) { validatePlanningAcknowledgement(pending, response) }
        response.addProperty("amount_minor", "200"); response.addProperty("schedule_version", 8)
        assertThrows(IllegalArgumentException::class.java) { validatePlanningAcknowledgement(pending, response) }
    }

    @Test fun settingsAcknowledgementMaySortSelectedAccountsWithoutChangingTheFrozenRequest() {
        val original = """{"expected_version":0,"reserve_minor":"100","account_ids":["$id","$user"],"base_currency":"KZT"}"""
        val pending = PendingLedgerMutation(user, key, "editplanningsettings", null, original)
        pending.validate()
        val sortedAck = JsonParser.parseString("""{"version":1,"reserve_minor":"100","account_ids":["$user","$id"],"base_currency":"KZT"}""").asJsonObject
        validatePlanningAcknowledgement(pending, sortedAck)
        assertEquals(original, pending.body)
        sortedAck["account_ids"].asJsonArray.remove(0)
        assertThrows(IllegalArgumentException::class.java) { validatePlanningAcknowledgement(pending, sortedAck) }
    }

    private fun forecast(balance: String, reserve: String, free: String, daily: String) = ForecastResponseDto("2026-10-17", "2026-10-18",
        "custom", "KZT", true, false, null, emptyList(), listOf(ForecastAccountDto(id, "Cash", "KZT", balance, balance)),
        emptyList(), balance, reserve, "0", free, daily, 2, "2025-10-16", false)
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
