package com.moneyflow.app.ui

import com.moneyflow.app.data.domain.AdvancedAnalyticsCategory
import com.moneyflow.app.data.domain.AdvancedAnalyticsRate
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.YearMonth

class AdvancedAnalyticsPresentationTest {
    @Test fun negativeAndExtremeBalancesNormalizeWithoutLongOverflow() {
        assertEquals(0f, analyticsPosition(Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE), 0f)
        assertEquals(.5f, analyticsPosition(0L, Long.MIN_VALUE, Long.MAX_VALUE), .000001f)
        assertEquals(1f, analyticsPosition(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE), 0f)
        assertEquals(.5f, analyticsPosition(0, 0, 0), 0f)
        assertEquals(0f, analyticsPosition(0, 0, Long.MAX_VALUE), 0f)
    }

    @Test fun directCategoryKeepsPriorMonthOperationsWhenCurrentAmountIsZero() {
        val child = AdvancedAnalyticsCategory("child", "Подкатегория", 200, 200, 400, -200)
        val root = AdvancedAnalyticsCategory("root", "Категория", 200, 0, 1000, -800, listOf(child))
        val rows = advancedCategoryChildren(root)
        val direct = rows.single { it.name == "Без подкатегории" }
        assertEquals("root", direct.categoryId)
        assertEquals(0L, direct.amountMinor)
        assertEquals(600L, direct.previousAmountMinor)
        assertEquals(-600L, direct.deltaMinor)
        assertEquals(listOf(child, direct), rows)
    }

    @Test fun drillDownKeepsAccountCurrencyAnalyticsAndExactCategoryScope() {
        val account = "00000000-0000-4000-8000-000000000001"
        val category = "00000000-0000-4000-8000-000000000002"
        val state = AdvancedAnalyticsUiState(accountId = account, currency = "KZT")
        val bounds = analyticsMonthBounds(YearMonth.of(2026, 10))
        assertEquals("2026-09-30T19:00:00Z", bounds.first)
        assertEquals("2026-10-31T19:00:00Z", bounds.second)
        val exact = analyticsHistoryFilter(state, "expense", bounds.first, bounds.second, category, false, true)
        assertEquals(account, exact.accountId)
        assertEquals("KZT", exact.currency)
        assertEquals(category, exact.categoryId)
        assertTrue(exact.categoryExact)
        assertTrue(exact.analyticsOnly)
        assertFalse(exact.uncategorized)
        assertFalse(exact.showDeleted)
        exact.validate()
        val none = analyticsHistoryFilter(state, "expense", bounds.first, bounds.second, uncategorized = true)
        assertNull(none.categoryId)
        assertTrue(none.uncategorized)
        assertFalse(none.categoryExact)
        none.validate()
    }

    @Test fun consolidatedDrillDownIncludesEveryCurrencyAndKeepsCategoryAndMonth() {
        val category = "00000000-0000-4000-8000-000000000002"
        // Even an old selected account must never narrow the consolidated report.
        val state = AdvancedAnalyticsUiState(mode = "consolidated", currency = "KZT",
            accountId = "00000000-0000-4000-8000-000000000001")
        val bounds = analyticsMonthBounds(YearMonth.of(2026, 10))
        val exact = analyticsHistoryFilter(state, "expense", bounds.first, bounds.second, category, categoryExact = true)
        assertNull(exact.accountId)
        assertNull(exact.currency)
        assertEquals(category, exact.categoryId)
        assertEquals("expense", exact.kind)
        assertEquals(bounds.first, exact.from)
        assertEquals(bounds.second, exact.to)
        assertTrue(exact.analyticsOnly)
        assertTrue(exact.categoryExact)
        assertFalse(exact.showDeleted)
        exact.validate()

        val none = analyticsHistoryFilter(state, "income", bounds.first, bounds.second, uncategorized = true)
        assertNull(none.currency)
        assertNull(none.accountId)
        assertNull(none.categoryId)
        assertTrue(none.uncategorized)
        none.validate()
    }

    @Test fun originalForeignCurrencyDrillDownStaysInSelectedCurrency() {
        val state = AdvancedAnalyticsUiState(mode = "original", currency = "USD")
        val bounds = analyticsMonthBounds(YearMonth.of(2026, 10))
        val filter = analyticsHistoryFilter(state, "income", bounds.first, bounds.second)
        assertEquals("USD", filter.currency)
        assertNull(filter.accountId)
        assertTrue(filter.analyticsOnly)
        filter.validate()
    }

    @Test fun rateCaptionIsReadableAndExposesPerCurrencyDateWithoutZeroingSmallRates() {
        assertEquals("1 USD ≈ 486,123457 KZT · 16.10.2026",
            analyticsConversionRateText(AdvancedAnalyticsRate("USD", BigDecimal("486.123456789000"), "2026-10-16")))
        assertEquals("1 EUR ≈ 500 KZT · дата курса не указана",
            analyticsConversionRateText(AdvancedAnalyticsRate("EUR", BigDecimal("5E+2"), null)))
        assertEquals("1 KZT = 1 KZT · базовая валюта",
            analyticsConversionRateText(AdvancedAnalyticsRate("KZT", BigDecimal.ONE, null)))
        assertEquals("1 USD ≈ 1,23457E-12 KZT · 16.10.2026",
            analyticsConversionRateText(AdvancedAnalyticsRate("USD", BigDecimal("0.00000000000123456789"), "2026-10-16")))
        assertEquals("1 EUR ≈ 1,234564 KZT · 16.10.2026",
            analyticsConversionRateText(AdvancedAnalyticsRate("EUR", BigDecimal("1.2345645"), "2026-10-16")))
    }
}
