package com.moneyflow.app.data

import com.google.gson.Gson
import com.moneyflow.app.data.remote.AdvancedAnalyticsReportDto
import com.moneyflow.app.data.remote.AdvancedAnalyticsSummaryDto
import com.moneyflow.app.data.remote.AnalyticsMonthPointDto
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** Synthetic zero-filled reports shared by the network/cache and ViewModel instrumentation tests. */
object AdvancedAnalyticsTestFixtures {
    const val ACCOUNT = "b5555555-5555-4555-8555-555555555555"
    fun report(month: String, currency: String = "KZT", months: Int = 6, accountId: String? = null,
        stamp: Instant = Instant.now()): String {
        val selected = YearMonth.parse(month)
        fun bounds(value: YearMonth) = "${value.atDay(1)}T00:00:00+05:00" to "${value.plusMonths(1).atDay(1)}T00:00:00+05:00"
        val today = stamp.atOffset(ZoneOffset.ofHours(5)).toLocalDate(); val current = YearMonth.from(today)
        val (from, to) = bounds(selected)
        val rows = (0 until months).map { index ->
            val date = selected.minusMonths((months - index - 1).toLong()); val (start, end) = bounds(date)
            AnalyticsMonthPointDto(date.toString(), start, end, "0", "0", "0", "1000", "1000", "0")
        }
        return Gson().toJson(AdvancedAnalyticsReportDto(month, currency, "UTC+05:00", months, accountId,
            listOf(accountId ?: ACCOUNT), from, to, stamp.toString(), today.toString(),
            when { selected < current -> "past"; selected > current -> "future"; else -> "current" },
            AdvancedAnalyticsSummaryDto("0", "0", "0", "0", "0", "0", "0", "0", "0"), rows, emptyList(), emptyList()))
    }
}
