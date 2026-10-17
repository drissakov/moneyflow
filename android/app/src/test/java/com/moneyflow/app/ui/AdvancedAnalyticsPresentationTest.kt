package com.moneyflow.app.ui

import com.moneyflow.app.data.domain.AdvancedAnalyticsCategory
import org.junit.Assert.*
import org.junit.Test
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
}
