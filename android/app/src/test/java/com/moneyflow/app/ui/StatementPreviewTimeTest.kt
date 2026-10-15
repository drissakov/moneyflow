package com.moneyflow.app.ui

import com.google.gson.Gson
import com.moneyflow.app.data.ImportRecoveryStorage
import com.moneyflow.app.data.imports.KazakhstanStatementTime
import com.moneyflow.app.data.imports.ParsedPdfStatement
import com.moneyflow.app.data.imports.ParsedStatement
import com.moneyflow.app.data.imports.StatementRow
import com.moneyflow.app.data.remote.ImportCommitRequest
import com.moneyflow.app.data.remote.ImportPreviewDto
import com.moneyflow.app.data.remote.ImportPreviewRow
import com.moneyflow.app.data.remote.ImportSelectionRow
import com.moneyflow.app.data.remote.PendingImportAction
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

class StatementPreviewTimeTest {
    private val account = "a7777777-7777-4777-8777-777777777777"
    private val user = "b7777777-7777-4777-8777-777777777777"
    private val draftId = "c7777777-7777-4777-8777-777777777777"
    private val key = "d7777777-7777-4777-8777-777777777777"
    private val dates = listOf("2024-02-29", "2024-03-01", "2026-01-01").map(LocalDate::parse)

    private fun pdf(bank: String = "kaspi") = ParsedPdfStatement(ParsedStatement(bank, "KZT", dates.first(), dates.last(), 1000L, 700L,
        dates.mapIndexed { index, date -> StatementRow(index + 1, date, "expense", 100, " Example ", sourcePage = 1) }), "a".repeat(64))

    @Test fun `new date-only PDF rows use fixed UTC plus five even before Kazakhstan timezone change`() {
        for (bank in listOf("kaspi", "halyk")) {
            val request = pdf(bank).toPreview(account)
            request.validate()
            assertEquals("Etc/GMT-5", request.timezone)
            assertEquals(listOf("2024-02-28T19:00:00Z", "2024-02-29T19:00:00Z", "2025-12-31T19:00:00Z"), request.rows.map { it.occurredAt })
            assertEquals(listOf(1, 2, 3), request.rows.map { it.rowId })
            assertEquals(listOf("100", "100", "100"), request.rows.map { it.amountMinor })
            assertTrue(request.rows.all { it.note == "Example" })
            assertEquals("1000", request.openingBalanceMinor); assertEquals("700", request.closingBalanceMinor)
        }
    }

    @Test fun `phone timezone does not change new preview bodies or fixed API local days`() {
        val original = TimeZone.getDefault()
        try {
            val bodies = listOf("Pacific/Honolulu", "Asia/Tokyo", "UTC", "Asia/Almaty").map { phoneZone ->
                TimeZone.setDefault(TimeZone.getTimeZone(phoneZone))
                val request = pdf().toPreview(account)
                request.rows.forEachIndexed { index, row ->
                    val instant = Instant.parse(row.occurredAt)
                    assertEquals(ZoneOffset.ofHours(5), ZoneId.of(request.timezone).rules.getOffset(instant))
                    assertEquals(dates[index], instant.atZone(ZoneId.of(request.timezone)).toLocalDate())
                }
                Gson().toJson(request)
            }
            assertEquals(1, bodies.distinct().size)
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun `existing preview ISO instants are validated without another five hour shift`() {
        val legacyRequest = pdf().toPreview(account).let { request -> request.copy(timezone = "Asia/Almaty", closingBalanceMinor = "900",
            rows = request.rows.take(1).map { it.copy(occurredAt = "2024-02-28T18:00:00Z") }) }
        val legacy = ImportPreviewDto(draftId, account, "kaspi", "KZT", "draft", "2026-12-31T00:00:00Z", "1000", "1000", "900",
            legacyRequest.rows.map { ImportPreviewRow(it.rowId, it.occurredAt, it.kind, it.amountMinor, it.note, "new", 0) })
        val before = Gson().toJson(legacy)
        legacy.validated(legacyRequest)
        assertEquals("2024-02-28T18:00:00Z", legacy.rows.single().occurredAt)
        assertEquals(before, Gson().toJson(legacy))
    }

    @Test fun `new preview policy leaves a restored old confirmation key and body unchanged`() {
        val oldAction = PendingImportAction(user, account, draftId, key, "commit",
            ImportCommitRequest(listOf(ImportSelectionRow(1, null, false, true))), 1, 0, "2024-02-29T18:00:00Z")
        oldAction.validate()
        val storage = object : ImportRecoveryStorage {
            private var pending: PendingImportAction? = oldAction
            override fun read(userId: String): PendingImportAction? = pending?.takeIf { it.userId == userId }
            override fun write(action: PendingImportAction) { pending = action }
            override fun clear(userId: String, expectedKey: String) { if (pending?.userId == userId && pending?.key == expectedKey) pending = null }
        }
        val before = Gson().toJson(oldAction)
        pdf().toPreview(account).validate()
        val resumed = requireNotNull(ImportRecoveryCoordinator(storage).pending(user))
        assertEquals(key, resumed.key)
        assertEquals(before, Gson().toJson(resumed))
        assertEquals("2024-02-29T18:00:00Z", resumed.createdAt)
    }
}
