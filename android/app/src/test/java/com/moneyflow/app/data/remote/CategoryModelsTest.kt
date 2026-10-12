package com.moneyflow.app.data.remote

import com.google.gson.Gson
import com.moneyflow.app.data.domain.AnalyticsCategory
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class CategoryModelsTest {
    private val rootId = "ee7d2d31-7369-4759-8125-f9d3967ded84"
    private val childId = "ee7d2d31-7369-4759-8125-f9d3967ded85"
    private val root = CategoryDto(rootId, "expense", "Продукты", null, true)
    private val child = CategoryDto(childId, "expense", "Кофе", rootId, false)

    @Test fun `category name normalization matches Go Unicode whitespace without changing other characters`() {
        assertEquals("Покупка автомобиля", normalizeCategoryName("  Покупка   автомобиля  "))
        assertEquals("Кофе дома", normalizeCategoryName("\u0085\u00a0Кофе\u2003\t\n\u202fдома\u3000"))
        assertEquals("", normalizeCategoryName("\u0085\u00a0\t\n\u2003"))
        assertEquals("Покупка\u200bавтомобиля", normalizeCategoryName("Покупка\u200bавтомобиля"))
        assertEquals("Кофе\u001cдома", normalizeCategoryName("Кофе\u001cдома"))
    }

    @Test fun `income stays flat and expenses allow only one level of children`() {
        assertEquals(2, CategoriesResponse(listOf(root, child)).toDomain().size)
        assertThrows(IllegalArgumentException::class.java) { child.copy(kind = "income").toDomain() }
        assertThrows(IllegalArgumentException::class.java) { CategoriesResponse(listOf(child)).toDomain() }
        assertThrows(IllegalArgumentException::class.java) {
            CategoriesResponse(listOf(root.copy(parentId = childId), child)).toDomain()
        }
        assertThrows(IllegalArgumentException::class.java) { root.copy(name = " ").toDomain() }
    }

    @Test fun `old durable requests and responses retain an absent nullable category`() {
        val legacy = Gson().fromJson("""{"account_id":"$rootId","kind":"expense","amount_minor":"1","note":"","occurred_at":"2026-10-12T00:00:00Z"}""",
            CreateTransactionRequest::class.java)
        legacy.validate()
        assertNull(legacy.categoryId)
        val transaction = TransactionDto(childId, rootId, "expense", "1", "KZT", "", legacy.occurredAt, legacy.occurredAt)
        assertTrue(matchesTransactionRequest(transaction.toDomain(), legacy))
        assertFalse(matchesTransactionRequest(transaction.copy(categoryId = rootId).toDomain(), legacy))
        assertTrue(matchesTransactionRequest(transaction.copy(categoryId = rootId).toDomain(), legacy.copy(categoryId = rootId)))
    }

    @Test fun `analytics sums complete server buckets and permits direct root spending`() {
        val analytics = analytics(listOf(AnalyticsCategoryDto(rootId, "Продукты", "120", listOf(
            AnalyticsCategoryDto(childId, "Кофе", "20", emptyList()),
        ))), "120").toDomain("2026-10", "KZT", "Asia/Qyzylorda")
        assertEquals(120L, analytics.expenseMinor)
        assertEquals(20L, analytics.expenses.single().children.single().amountMinor)
        assertEquals(BigDecimal("16.7"), analytics.expenses.single().children.single().percentage(120))
        assertEquals(BigDecimal.ZERO, AnalyticsCategory(null, "Без категории", 0).percentage(0))
    }

    @Test fun `analytics rejects mismatched periods inconsistent totals child overflow and integer overflow`() {
        val valid = analytics(listOf(AnalyticsCategoryDto(rootId, "Продукты", "120", emptyList())), "120")
        assertThrows(IllegalArgumentException::class.java) { valid.toDomain("2026-09", "KZT", "Asia/Qyzylorda") }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(expenseMinor = "121").toDomain("2026-10", "KZT", "Asia/Qyzylorda") }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(expenses = listOf(
            AnalyticsCategoryDto(rootId, "Продукты", "120", listOf(AnalyticsCategoryDto(childId, "Кофе", "121", emptyList()))),
        )).toDomain("2026-10", "KZT", "Asia/Qyzylorda") }
        assertThrows(IllegalArgumentException::class.java) { analytics(listOf(
            AnalyticsCategoryDto(rootId, "Продукты", Long.MAX_VALUE.toString(), emptyList()),
            AnalyticsCategoryDto(null, "Без категории", "1", emptyList()),
        ), Long.MAX_VALUE.toString()).toDomain("2026-10", "KZT", "Asia/Qyzylorda") }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(income = listOf(
            AnalyticsCategoryDto(rootId, "Зарплата", "0", listOf(AnalyticsCategoryDto(childId, "Кофе", "0", emptyList()))),
        )).toDomain("2026-10", "KZT", "Asia/Qyzylorda") }
    }

    private fun analytics(expenses: List<AnalyticsCategoryDto>, total: String) = MonthlyAnalyticsDto(
        "2026-10", "KZT", "Asia/Qyzylorda", "0", total, emptyList(), expenses,
    )
}
