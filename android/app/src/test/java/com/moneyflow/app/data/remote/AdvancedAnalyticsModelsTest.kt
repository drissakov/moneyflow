package com.moneyflow.app.data.remote

import com.google.gson.Gson
import com.moneyflow.app.data.domain.AdvancedAnalyticsFilter
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.YearMonth
import java.time.LocalDate
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

    @Test fun consolidatedModeRequiresAnExplicitKztAllAccountSnapshotWithoutClientMoneyRounding() {
        val converted = fixture().copy(mode = "consolidated", conversion = conversion())
        val report = converted.toDomain(filter.copy(mode = "consolidated", valuation = "latest_snapshot"))
        assertEquals("consolidated", report.mode)
        assertEquals(BigDecimal("489.12345678901234567890"), report.conversion!!.rates.last().rate)
        assertEquals(1250L, report.series.last().closingBalanceMinor)
        assertEquals("latest_snapshot", report.conversion.policy)
        assertThrows(IllegalArgumentException::class.java) { converted.toDomain(filter) }
        assertThrows(IllegalArgumentException::class.java) { fixture().toDomain(filter.copy(mode = "consolidated")) }
        assertThrows(IllegalArgumentException::class.java) { converted.copy(conversion = null).toDomain(filter.copy(mode = "consolidated")) }
        assertThrows(IllegalArgumentException::class.java) { filter.copy(mode = "consolidated", currency = "USD").validate() }
        assertThrows(IllegalArgumentException::class.java) { filter.copy(mode = "consolidated", accountId = account).validate() }
        assertThrows(IllegalArgumentException::class.java) { filter.copy(mode = "unknown").validate() }
    }

    @Test fun originalReportsRemainCompatibleWithOlderJsonAndRejectUnexpectedConversion() {
        val gson = Gson()
        val oldJson = gson.toJsonTree(fixture()).asJsonObject.apply { remove("mode"); remove("conversion") }
        val oldReport = gson.fromJson(oldJson, AdvancedAnalyticsReportDto::class.java).toDomain(filter)
        assertEquals("original", oldReport.mode)
        assertNull(oldReport.conversion)
        assertEquals("original", fixture().copy(mode = "original").toDomain(filter).mode)
        assertThrows(IllegalArgumentException::class.java) { fixture().copy(conversion = conversion()).toDomain(filter) }
        assertThrows(IllegalArgumentException::class.java) { fixture().copy(mode = "surprise").toDomain(filter) }
    }

    @Test fun conversionRejectsUnsortedDuplicateUnsupportedOrImpreciseRatesAndUnstampedForeignSnapshots() {
        val base = conversion()
        val invalid = listOf(
            base.copy(policy = "historical"), base.copy(provider = "Unknown"),
            base.copy(rates = base.rates.reversed()), base.copy(rates = base.rates + base.rates.last()),
            base.copy(rates = listOf(AdvancedAnalyticsRateDto("ZZZ", "1", "2026-10-16"))),
            base.copy(rates = listOf(AdvancedAnalyticsRateDto("KZT", "2", null))),
            base.copy(rates = listOf(AdvancedAnalyticsRateDto("KZT", "1", "2026-10-16"))),
            base.copy(fetchedAt = null), base.copy(fetchedAt = "not-an-instant"),
            base.copy(rates = listOf(AdvancedAnalyticsRateDto("USD", "1", null))),
            base.copy(rates = listOf(AdvancedAnalyticsRateDto("USD", "1", "2026-02-30"))),
            base.copy(rates = emptyList()),
        ) + listOf("0", "-1", "1e2", "NaN", "01.5", "0." + "1".repeat(1025), "9".repeat(2049)).map {
            base.copy(rates = listOf(AdvancedAnalyticsRateDto("USD", it, "2026-10-16")))
        }
        invalid.forEach { snapshot ->
            assertThrows(RuntimeException::class.java) {
                fixture().copy(mode = "consolidated", conversion = snapshot).toDomain(filter.copy(mode = "consolidated", valuation = "latest_snapshot"))
            }
        }
    }

    @Test fun identityAndEmptyConsolidatedReportsNeedNoRemoteFetchAndStaleForeignRatesRemainExplicit() {
        val identity = AdvancedAnalyticsConversionDto("latest_snapshot", "Frankfurter", null, false,
            listOf(AdvancedAnalyticsRateDto("KZT", "1", null)))
        assertNull(identity.toDomain(true).fetchedAt)
        assertTrue(identity.copy(rates = emptyList()).toDomain(false).rates.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { identity.toDomain(false) }
        assertThrows(IllegalArgumentException::class.java) { identity.copy(stale = true).toDomain(true) }
        assertThrows(IllegalArgumentException::class.java) { identity.copy(fetchedAt = "2026-10-17T09:00:00Z").toDomain(true) }
        assertTrue(conversion().copy(stale = true).toDomain(true).stale)
    }

    @Test fun historicalReportReconcilesDailyMoneyMovementAndMonthEndRevaluationWithoutClientConversion() {
        val report = historicalFixture().toDomain(filter.copy(mode = "consolidated"))
        assertEquals("historical", report.conversion!!.policy)
        assertEquals("2026-07-31", report.conversion.fromDate)
        assertEquals("2026-10-17", report.conversion.toDate)
        assertEquals("2026-09-30", report.series.last().openingValuationDate)
        assertEquals("2026-10-17", report.series.last().valuationDate)
        assertEquals(100L, report.series.last().convertedLedgerChangeMinor)
        assertEquals(-10L, report.series.last().fxRevaluationMinor)
        assertEquals(90L, report.series.last().ledgerChangeMinor)
        assertEquals(300L, report.summary.incomeMinor)
        assertEquals(listOf("2026-07-31", "2026-08-31", "2026-09-30", "2026-10-17"), report.conversion.rates.map { it.valuationDate })
        val roundTrip = Gson().fromJson(Gson().toJson(historicalFixture()), AdvancedAnalyticsReportDto::class.java)
        assertEquals(report, roundTrip.toDomain(filter.copy(mode = "consolidated")))
    }

    @Test fun historicalAndLatestSnapshotsCannotMasqueradeAsEachOtherOrAcceptPartialBalanceMetadata() {
        val historical = historicalFixture(); val expected = filter.copy(mode = "consolidated")
        assertThrows(IllegalArgumentException::class.java) { historical.toDomain(expected.copy(valuation = "latest_snapshot")) }
        assertThrows(IllegalArgumentException::class.java) { fixture().copy(mode = "consolidated", conversion = conversion()).toDomain(expected) }
        listOf(
            historical.series.last().copy(convertedLedgerChangeMinor = null),
            historical.series.last().copy(fxRevaluationMinor = "-9"),
            historical.series.last().copy(convertedLedgerChangeMinor = Long.MIN_VALUE.toString(), fxRevaluationMinor = "90"),
            historical.series.last().copy(valuationDate = "2026-10-31"),
            historical.series.last().copy(openingValuationDate = "2026-10-01"),
        ).forEach { point ->
            assertThrows(IllegalArgumentException::class.java) { historical.copy(series = historical.series.dropLast(1) + point).toDomain(expected) }
        }
        assertThrows(IllegalArgumentException::class.java) { expected.copy(valuation = "unknown").validate() }
    }

    @Test fun historicalQuotesRejectFutureOldMissingBoundaryDuplicateOrWrongPublicationDates() {
        val base = historicalFixture(); val metadata = base.conversion!!; val rate = metadata.rates.last()
        val expected = filter.copy(mode = "consolidated")
        listOf(
            metadata.copy(rates = metadata.rates.drop(1)),
            metadata.copy(rates = metadata.rates.reversed()),
            metadata.copy(rates = metadata.rates + rate),
            metadata.copy(fromDate = "2026-08-01"), metadata.copy(toDate = "2026-10-31"), metadata.copy(maxAgeDays = 15),
            metadata.copy(rates = metadata.rates.dropLast(1) + rate.copy(rateDate = "2026-10-18", targetRateDate = "2026-10-18")),
            metadata.copy(rates = metadata.rates.dropLast(1) + rate.copy(rateDate = "2026-10-02", targetRateDate = "2026-10-02")),
            metadata.copy(rates = metadata.rates.dropLast(1) + rate.copy(targetRateDate = null)),
            metadata.copy(rates = metadata.rates.dropLast(1) + rate.copy(sourceRateDate = "2026-10-16")),
            metadata.copy(rates = metadata.rates.dropLast(1) + rate.copy(currency = "EUR")),
            metadata.copy(rates = metadata.rates.dropLast(1) + rate.copy(rateDate = "2026-10-17")),
            metadata.copy(fetchedAt = null),
        ).forEach { invalid -> assertThrows(RuntimeException::class.java) { base.copy(conversion = invalid).toDomain(expected) } }
    }

    @Test fun historicalCarryForwardAcceptsFourteenDaysAndTracksBothCrossRatePublicationDates() {
        val from = LocalDate.parse("2026-10-01"); val to = LocalDate.parse("2026-10-17")
        val rate = AdvancedAnalyticsRateDto("EUR", "500.125", "2026-10-03", "2026-10-17", "2026-10-03", "2026-10-16")
        assertEquals("2026-10-03", rate.toDomain(from, to).rateDate)
        assertEquals("2026-10-16", rate.toDomain(from, to).targetRateDate)
        assertThrows(IllegalArgumentException::class.java) { rate.copy(rateDate = "2026-10-16").toDomain(from, to) }
        assertThrows(IllegalArgumentException::class.java) { rate.copy(sourceRateDate = "2026-10-02", rateDate = "2026-10-02").toDomain(from, to) }
    }

    @Test fun historicalKztOnlyNeedsNoNetworkTimestampAndNeverFabricatesFutureRates() {
        val base = historicalFixture(); val expected = filter.copy(mode = "consolidated")
        val identity = base.conversion!!.copy(rates = emptyList(), fetchedAt = null)
        assertTrue(base.copy(conversion = identity).toDomain(expected).conversion!!.rates.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { base.copy(conversion = identity.copy(stale = true)).toDomain(expected) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(conversion = identity.copy(fetchedAt = base.asOf)).toDomain(expected) }
        assertThrows(IllegalArgumentException::class.java) { base.copy(month = "2026-11", from = "2026-11-01T00:00:00+05:00", to = "2026-12-01T00:00:00+05:00", periodStatus = "future")
            .toDomain(expected.copy(month = "2026-11")) }
    }

    private fun historicalFixture(): AdvancedAnalyticsReportDto {
        val base = fixture()
        val dates = listOf("2026-07-31", "2026-08-31", "2026-09-30", "2026-10-17")
        return base.copy(mode = "consolidated", conversion = AdvancedAnalyticsConversionDto("historical", "Frankfurter", base.asOf, false,
            dates.map { val observed = LocalDate.parse(it).minusDays(1).toString(); AdvancedAnalyticsRateDto("USD", "452.65", observed, it, null, observed) },
            dates.first(), dates.last(), 14), series = base.series.mapIndexed { index, row -> row.copy(
                openingValuationDate = dates[index], valuationDate = dates[index + 1],
                convertedLedgerChangeMinor = if (index == 2) "100" else row.ledgerChangeMinor, fxRevaluationMinor = if (index == 2) "-10" else "0") })
    }

    private fun conversion() = AdvancedAnalyticsConversionDto("latest_snapshot", "Frankfurter", "2026-10-17T08:30:00Z", false,
        listOf(AdvancedAnalyticsRateDto("KZT", "1", null), AdvancedAnalyticsRateDto("USD", "489.12345678901234567890", "2026-10-16")))

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
