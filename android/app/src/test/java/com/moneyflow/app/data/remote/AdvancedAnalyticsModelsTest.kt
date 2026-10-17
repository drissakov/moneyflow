package com.moneyflow.app.data.remote

import com.google.gson.Gson
import com.moneyflow.app.data.domain.AdvancedAnalyticsFilter
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.YearMonth
import java.time.ZoneOffset

class AdvancedAnalyticsModelsTest {
    private val account = "a7777777-7777-4777-8777-777777777777"
    private val salary = "b7777777-7777-4777-8777-777777777777"
    private val food = "c7777777-7777-4777-8777-777777777777"
    private val coffee = "d7777777-7777-4777-8777-777777777777"
    private val filter = AdvancedAnalyticsFilter("2026-10", "KZT", 3)

    @Test fun reportReconcilesBothMonthsAndKeepsLedgerMovementDistinctFromAnalyticalNet() {
        val report = fixture().toDomain(filter)
        assertEquals(200L, report.summary.netMinor)
        assertEquals(90L, report.series.last().ledgerChangeMinor)
        assertEquals(1250L, report.series.last().closingBalanceMinor)
        assertEquals(20L, report.expenses.single().directAmountMinor)
        assertEquals(BigDecimal("80.0"), report.expenses.single().children.single().percentage(100))
        assertEquals("2026-09-30T19:00:00Z", java.time.Instant.parse(report.from).toString())
    }

    @Test fun zeroCurrentPreviousOnlyBucketRemainsVisibleWithExactNegativeChange() {
        val previousOnly = AdvancedAnalyticsCategoryDto(food, "Food", "0", "0", "250", "-250", emptyList()).toDomain(true)
        assertEquals(0L, previousOnly.amountMinor); assertEquals(-250L, previousOnly.deltaMinor)
        assertEquals(BigDecimal.ZERO, previousOnly.percentage(0))
    }

    @Test fun wrongAccountCurrencySpanOrMonthCannotBePresentedAsTheRequestedReport() {
        listOf(filter.copy(accountId = account), filter.copy(currency = "USD"), filter.copy(months = 6), filter.copy(month = "2026-09")).forEach {
            assertThrows(IllegalArgumentException::class.java) { fixture().toDomain(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { fixture().copy(accountIds = listOf(account, account)).toDomain(filter) }
    }

    @Test fun gapsInZeroFilledMonthsAndDiscontinuousBalancesAreRejected() {
        val valid = fixture()
        assertThrows(IllegalArgumentException::class.java) { valid.copy(series = valid.series.drop(1)).toDomain(filter) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(series = valid.series.reversed()).toDomain(filter) }
        val broken = valid.series.toMutableList().apply { this[1] = this[1].copy(openingBalanceMinor = "999", ledgerChangeMinor = "161") }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(series = broken).toDomain(filter) }
    }

    @Test fun bucketDirectSubcategoryAndPreviousTotalsMustReconcileWithoutOverflow() {
        val valid = fixture()
        assertThrows(IllegalArgumentException::class.java) { valid.copy(expenses = listOf(valid.expenses.single().copy(directAmountMinor = "21"))).toDomain(filter) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(expenses = listOf(valid.expenses.single().copy(previousAmountMinor = "39", deltaMinor = "61"))).toDomain(filter) }
        assertThrows(IllegalArgumentException::class.java) { AdvancedAnalyticsCategoryDto(food, "Food", Long.MAX_VALUE.toString(), Long.MAX_VALUE.toString(), "0", Long.MAX_VALUE.toString(),
            listOf(AdvancedAnalyticsCategoryDto(coffee, "Coffee", "1", "1", "0", "1", emptyList()))).toDomain(true) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(income = listOf(valid.expenses.single())).toDomain(filter) }
    }

    @Test fun summaryNetAndDeltasRejectWrappedOrRoundedValues() {
        val valid = fixture()
        assertThrows(IllegalArgumentException::class.java) { valid.copy(summary = valid.summary.copy(netMinor = "201")).toDomain(filter) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(summary = valid.summary.copy(netDeltaMinor = "39")).toDomain(filter) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(summary = valid.summary.copy(incomeMinor = "0300")).toDomain(filter) }
    }

    @Test fun timezoneMidnightBoundsAndCurrentPeriodStatusArePartOfTheContract() {
        val valid = fixture()
        assertThrows(IllegalArgumentException::class.java) { valid.copy(timezone = "UTC").toDomain(filter) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(from = "2026-10-01T00:00:00Z").toDomain(filter) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(periodStatus = "past").toDomain(filter) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(today = "2026-10-16").toDomain(filter) }
        val roundTrip = Gson().fromJson(Gson().toJson(valid), AdvancedAnalyticsReportDto::class.java).toDomain(filter)
        assertEquals("current", roundTrip.periodStatus)
    }

    @Test fun boundedSpanAndFiscalMonthValidateBeforeNetwork() {
        listOf(3, 6, 12).forEach { filter.copy(months = it).validate() }
        listOf(0, 1, 4, 13).forEach { assertThrows(IllegalArgumentException::class.java) { filter.copy(months = it).validate() } }
        assertThrows(IllegalArgumentException::class.java) { filter.copy(month = "1900-12").validate() }
        assertThrows(IllegalArgumentException::class.java) { filter.copy(month = "2200-01").validate() }
        LedgerFilter(currency = "KZT", analyticsOnly = true, uncategorized = true).validate()
        LedgerFilter(currency = "KZT", analyticsOnly = true, categoryId = food, categoryExact = true).validate()
        assertThrows(IllegalArgumentException::class.java) { LedgerFilter(categoryExact = true).validate() }
        assertThrows(IllegalArgumentException::class.java) { LedgerFilter(categoryId = food, uncategorized = true).validate() }
        assertThrows(IllegalArgumentException::class.java) { LedgerFilter(analyticsOnly = true, showDeleted = true).validate() }
        assertThrows(IllegalArgumentException::class.java) { LedgerFilter(currency = "ZZZ").validate() }
    }

    private fun fixture(): AdvancedAnalyticsReportDto {
        val offset = ZoneOffset.ofHours(5)
        fun bounds(month: String): Pair<String, String> { val parsed = YearMonth.parse(month); return "${parsed.atDay(1)}T00:00:00+05:00" to "${parsed.plusMonths(1).atDay(1)}T00:00:00+05:00" }
        fun point(month: String, income: String, expense: String, net: String, opening: String, closing: String, change: String): AnalyticsMonthPointDto {
            val (from, to) = bounds(month); return AnalyticsMonthPointDto(month, from, to, income, expense, net, opening, closing, change)
        }
        val (from, to) = bounds("2026-10")
        return AdvancedAnalyticsReportDto("2026-10", "KZT", "UTC+05:00", 3, null, listOf(account), from, to, "2026-10-17T09:00:00Z", "2026-10-17", "current",
            AdvancedAnalyticsSummaryDto("300", "100", "200", "200", "40", "160", "100", "60", "40"), listOf(
                point("2026-08", "0", "0", "0", "1000", "1000", "0"), point("2026-09", "200", "40", "160", "1000", "1160", "160"),
                point("2026-10", "300", "100", "200", "1160", "1250", "90")),
            listOf(AdvancedAnalyticsCategoryDto(salary, "Salary", "300", "300", "200", "100", emptyList())),
            listOf(AdvancedAnalyticsCategoryDto(food, "Food", "100", "20", "40", "60", listOf(AdvancedAnalyticsCategoryDto(coffee, "Coffee", "80", "80", "30", "50", emptyList())))))
    }
}
