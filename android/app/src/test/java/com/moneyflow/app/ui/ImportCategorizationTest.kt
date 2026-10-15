package com.moneyflow.app.ui

import com.moneyflow.app.data.domain.Category
import com.moneyflow.app.data.remote.ImportPreviewRow
import org.junit.Assert.*
import org.junit.Test

class ImportCategorizationTest {
    private val food = "a8888888-8888-4888-8888-888888888881"
    private val coffee = "a8888888-8888-4888-8888-888888888882"
    private val custom = "a8888888-8888-4888-8888-888888888883"
    private val salary = "a8888888-8888-4888-8888-888888888884"
    private val archived = "a8888888-8888-4888-8888-888888888885"
    private val archivedChild = "a8888888-8888-4888-8888-888888888886"
    private val unknown = "a8888888-8888-4888-8888-888888888887"
    private val key = "v1:" + "a".repeat(64)
    private val categories = listOf(
        Category(food, "expense", "Food", null, true),
        Category(coffee, "expense", "Coffee", food, true),
        Category(custom, "expense", "My category", null, false),
        Category(salary, "income", "Salary", null, true),
        Category(archived, "expense", "Old group", null, false, archived = true),
        Category(archivedChild, "expense", "Child under old group", archived, false),
    )
    private fun row(id: Int, kind: String = "expense", duplicate: String = "new", suggestion: String? = coffee,
        match: String? = key) = ImportPreviewRow(id, "2026-01-01T19:00:00Z", kind, "100", "Coffee shop", duplicate,
            if (duplicate == "possible") 1 else 0, suggestion, suggestion?.let { "history" }, match)

    @Test fun `initial autofill accepts active matching roots children and custom categories only`() {
        val rows = listOf(
            row(1, suggestion = food), row(2, suggestion = coffee), row(3, suggestion = custom),
            row(4, kind = "income", suggestion = salary), row(5, suggestion = archived),
            row(6, suggestion = archivedChild), row(7, suggestion = unknown), row(8, suggestion = salary),
            row(9, suggestion = null), row(10, kind = "income", suggestion = coffee),
        )
        val choices = initialImportChoices(rows, categories)
        assertEquals(listOf(food, coffee, custom, salary), (1..4).map { choices.getValue(it).categoryId })
        (5..10).forEach { assertNull("Invalid suggestion row $it", choices.getValue(it).categoryId) }
        assertTrue(choices.values.all { it.selected && !it.categoryEdited && !it.allowPossibleDuplicate && !it.excludeFromAnalytics && !it.transferReviewed })
        val childWithoutParent = categories.filterNot { it.id == food }
        assertFalse(activeImportCategory(childWithoutParent, coffee, "expense"))
        assertFalse(activeImportCategory(categories, null, "expense"))
    }

    @Test fun `autofill never selects exact or possible duplicates`() {
        val choices = initialImportChoices(listOf(row(1), row(2, duplicate = "possible"), row(3, duplicate = "exact")), categories)
        assertTrue(choices.getValue(1).selected)
        assertFalse(choices.getValue(2).selected); assertFalse(choices.getValue(3).selected)
        assertFalse(choices.getValue(2).allowPossibleDuplicate)
        assertTrue(choices.values.all { !it.transferReviewed })
    }

    @Test fun `bulk uses only selected nonmovement same kind and versioned key without overwriting manual choices`() {
        val rows = listOf(row(1), row(2), row(3, duplicate = "possible"), row(4), row(5), row(6), row(7),
            row(8, kind = "income"), row(9, match = "v1:" + "b".repeat(64)), row(10, duplicate = "exact"),
            row(11), row(12, match = null))
        val choices = linkedMapOf(
            1 to ImportRowChoice(true, custom, categoryEdited = true),
            2 to ImportRowChoice(true, coffee),
            3 to ImportRowChoice(true, coffee, allowPossibleDuplicate = true, transferReviewed = true),
            4 to ImportRowChoice(false, coffee),
            5 to ImportRowChoice(true, coffee, excludeFromAnalytics = true, transferReviewed = true),
            6 to ImportRowChoice(true, null, categoryEdited = true), // An explicit manual clear must win.
            7 to ImportRowChoice(true, food, categoryEdited = true),
            8 to ImportRowChoice(true, salary),
            9 to ImportRowChoice(true, coffee),
            10 to ImportRowChoice(true, coffee), // Defensive: even a bad selected exact row must not be changed.
            11 to ImportRowChoice(true, custom),
            12 to ImportRowChoice(true, coffee),
        )
        val before = choices.toMap()
        assertEquals(listOf(2, 3), similarImportCategoryRows(rows, choices, categories, 1))
        val result = applyImportCategoryToSimilar(rows, choices, categories, 1)
        listOf(2, 3).forEach { id ->
            assertEquals(custom, result.getValue(id).categoryId)
            assertTrue(result.getValue(id).categoryEdited)
            assertEquals(before.getValue(id), result.getValue(id).copy(categoryId = before.getValue(id).categoryId, categoryEdited = false))
        }
        (before.keys - setOf(2, 3)).forEach { assertEquals("Unrelated row $it", before[it], result[it]) }
        assertEquals(before, choices) // The source map and every review flag remain unchanged.
        assertNull(result.getValue(6).categoryId)
        assertEquals(result, applyImportCategoryToSimilar(rows, result, categories, 1))
    }

    @Test fun `bulk needs an explicitly edited active source and cannot use malformed keys`() {
        val target = row(2, suggestion = food)
        val rows = listOf(row(1), target)
        val source = ImportRowChoice(true, coffee, categoryEdited = true)
        val choices = mapOf(1 to source, 2 to ImportRowChoice(true, food))
        listOf(source.copy(categoryEdited = false), source.copy(selected = false), source.copy(excludeFromAnalytics = true),
            source.copy(categoryId = null), source.copy(categoryId = archived), source.copy(categoryId = archivedChild),
            source.copy(categoryId = salary), source.copy(categoryId = unknown)).forEach { invalid ->
            assertTrue(similarImportCategoryRows(rows, choices + (1 to invalid), categories, 1).isEmpty())
            assertEquals(choices + (1 to invalid), applyImportCategoryToSimilar(rows, choices + (1 to invalid), categories, 1))
        }
        listOf(null, "", "a".repeat(64), "v2:" + "a".repeat(64), "v1:" + "A".repeat(64), "v1:" + "a".repeat(63),
            "v1:" + "a".repeat(65), " $key").forEach { invalid ->
            assertTrue(similarImportCategoryRows(listOf(row(1, match = invalid), target), choices, categories, 1).isEmpty())
        }
        assertTrue(similarImportCategoryRows(listOf(row(1, duplicate = "exact"), target), choices, categories, 1).isEmpty())
        assertTrue(similarImportCategoryRows(rows, choices - 1, categories, 1).isEmpty())
        assertTrue(similarImportCategoryRows(rows, choices, categories, 99).isEmpty())
    }
}
