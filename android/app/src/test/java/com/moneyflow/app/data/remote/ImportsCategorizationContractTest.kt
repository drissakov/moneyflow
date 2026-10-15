package com.moneyflow.app.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ImportsCategorizationContractTest {
    private val account = "a9999999-9999-4999-8999-999999999999"
    private val draft = "b9999999-9999-4999-8999-999999999999"
    private val category = "c9999999-9999-4999-8999-999999999999"
    private val key = "v1:" + "a".repeat(64)
    private val request = ImportPreviewRequest(account, "kaspi", "KZT", "a".repeat(64), "Etc/GMT-5", "1000", "900",
        listOf(ImportSourceRow(1, "2026-01-01T19:00:00Z", "expense", "100", "Coffee")))
    private fun preview() = ImportPreviewDto(draft, account, "kaspi", "KZT", "draft", "2026-12-31T00:00:00Z", "1000", "1000", "900",
        listOf(ImportPreviewRow(1, "2026-01-01T19:00:00Z", "expense", "100", "Coffee", "new", 0)))

    @Test fun `old server responses without suggestion fields remain valid`() {
        val json = JsonParser.parseString(Gson().toJson(preview())).asJsonObject
        val row = json.getAsJsonArray("rows")[0].asJsonObject
        listOf("suggested_category_id", "category_suggestion_source", "category_match_key").forEach(row::remove)
        val decoded = Gson().fromJson(json, ImportPreviewDto::class.java).validated(request)
        assertNull(decoded.rows.single().suggestedCategoryId)
        assertNull(decoded.rows.single().categorySuggestionSource)
        assertNull(decoded.rows.single().categoryMatchKey)
        assertEquals("2026-01-01T19:00:00Z", decoded.rows.single().occurredAt)
    }

    @Test fun `supported suggestion sources and optional opaque keys preserve financial source data`() {
        for (source in listOf("history", "rule")) {
            val response = preview().copy(rows = preview().rows.map { it.copy(suggestedCategoryId = category,
                categorySuggestionSource = source, categoryMatchKey = key) })
            val decoded = Gson().fromJson(Gson().toJson(response), ImportPreviewDto::class.java).validated(request)
            assertEquals(category, decoded.rows.single().suggestedCategoryId)
            assertEquals(source, decoded.rows.single().categorySuggestionSource)
            assertEquals(key, decoded.rows.single().categoryMatchKey)
            assertEquals(request.rows.single().amountMinor, decoded.rows.single().amountMinor)
            assertEquals(request.rows.single().note, decoded.rows.single().note)
        }
        preview().copy(rows = preview().rows.map { it.copy(categoryMatchKey = key) }).validated(request)
        preview().copy(rows = preview().rows.map { it.copy(suggestedCategoryId = category, categorySuggestionSource = "history") }).validated(request)
    }

    @Test fun `unpaired unknown or malformed category suggestions are rejected`() {
        val base = preview().rows.single()
        val invalid = listOf(
            base.copy(suggestedCategoryId = category),
            base.copy(categorySuggestionSource = "history"),
            base.copy(suggestedCategoryId = category, categorySuggestionSource = "ai"),
            base.copy(suggestedCategoryId = category, categorySuggestionSource = ""),
            base.copy(suggestedCategoryId = "not-a-uuid", categorySuggestionSource = "history"),
            base.copy(suggestedCategoryId = "a9999999999949998999999999999999", categorySuggestionSource = "rule"),
        )
        invalid.forEach { row -> assertThrows(IllegalArgumentException::class.java) { preview().copy(rows = listOf(row)).validated(request) } }
    }

    @Test fun `match keys must use the exact lowercase v1 format`() {
        val invalid = listOf("", "a".repeat(64), "v2:" + "a".repeat(64), "v1:" + "A".repeat(64), "v1:" + "g".repeat(64),
            "v1:" + "a".repeat(63), "v1:" + "a".repeat(65), " $key", "$key ")
        invalid.forEach { value -> assertThrows(IllegalArgumentException::class.java) {
            preview().copy(rows = preview().rows.map { it.copy(categoryMatchKey = value) }).validated(request)
        } }
    }
}
