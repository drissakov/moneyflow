package com.moneyflow.app.ui

import com.moneyflow.app.data.domain.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class CategoryPickerGroupsTest {
    private val food = Category("food", "expense", "Продукты", null, true)
    private val coffee = Category("coffee", "expense", "Кофе", "food", false)
    private val groceries = Category("groceries", "expense", "Азық-түлік", "food", false)
    private val car = Category("car", "expense", "Покупка автомобиля", null, false)
    private val salary = Category("salary", "income", "Зарплата", null, true)
    private val categories = listOf(food, coffee, groceries, car, salary)

    @Test fun `root search includes its children and preserves category order`() {
        assertEquals(listOf(CategoryPickerGroup(food, listOf(coffee, groceries))),
            categoryPickerGroups(categories, "expense", false, " ПРОДУКТЫ "))
        assertEquals(listOf(food, car), categoryPickerGroups(categories, "expense", false, "").map { it.root })
    }

    @Test fun `child search retains parent context with only matching children`() {
        assertEquals(listOf(CategoryPickerGroup(food, listOf(coffee))),
            categoryPickerGroups(categories, "expense", false, "КОФЕ"))
        assertEquals(listOf(CategoryPickerGroup(food, listOf(groceries))),
            categoryPickerGroups(categories, "expense", false, "АЗЫҚ"))
        assertTrue(categoryPickerGroups(categories, "expense", false, "нет такого").isEmpty())
    }

    @Test fun `income and parent pickers exclude children and other transaction kinds`() {
        assertEquals(listOf(CategoryPickerGroup(salary, emptyList())), categoryPickerGroups(categories, "income", false, ""))
        assertEquals(listOf(CategoryPickerGroup(food, emptyList()), CategoryPickerGroup(car, emptyList())),
            categoryPickerGroups(categories, "expense", true, ""))
        assertTrue(categoryPickerGroups(categories, "expense", true, "КОФЕ").isEmpty())
        val invalidIncomeChild = Category("incomeChild", "income", "Бонус", "salary", false)
        assertTrue(categoryPickerGroups(categories + invalidIncomeChild, "income", false, "Бонус").isEmpty())
    }

    @Test fun `search case folding is independent of device locale and never mixes kinds`() {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val latin = Category("gift", "income", "GIFT", null, false)
            assertEquals(listOf(CategoryPickerGroup(latin, emptyList())), categoryPickerGroups(categories + latin, "income", false, "gift"))
            val wrongKind = coffee.copy(id = "wrong", kind = "income", name = "Чужая")
            assertTrue(categoryPickerGroups(categories + wrongKind, "expense", false, "Чужая").isEmpty())
        } finally {
            Locale.setDefault(oldLocale)
        }
    }
}
