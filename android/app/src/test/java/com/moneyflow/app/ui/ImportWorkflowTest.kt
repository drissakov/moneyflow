package com.moneyflow.app.ui

import com.moneyflow.app.data.ImportRecoveryStorage
import com.moneyflow.app.data.remote.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ImportWorkflowTest {
    private val userA = "74b729ee-1188-419c-9b47-8c39e63181d2"
    private val userB = "74b729ee-1188-419c-9b47-8c39e63181d3"
    private fun draft() = ImportPreviewDto("74b729ee-1188-419c-9b47-8c39e63181d4", "74b729ee-1188-419c-9b47-8c39e63181d5",
        "kaspi", "KZT", "draft", Instant.now().plusSeconds(3600).toString(), "0", "0", "-200",
        listOf(1, 2).map { ImportPreviewRow(it, "2026-01-01T00:00:00Z", "expense", "100", "Example", "new", 0) })

    @Test fun `identical real purchases remain distinct selected rows`() {
        val recovery = ImportRecoveryCoordinator(MemoryStore())
        val action = recovery.prepareCommit(userA, draft(), mapOf(1 to ImportRowChoice(true), 2 to ImportRowChoice(true)))
        assertEquals(listOf(1, 2), action.selection!!.rows.map { it.rowId })
    }

    @Test fun `uncertain commit retains exact key selection and flags across controller recreation`() {
        val storage = MemoryStore(); val recovery = ImportRecoveryCoordinator(storage)
        val choices = mutableMapOf(1 to ImportRowChoice(true, excludeFromAnalytics = true), 2 to ImportRowChoice(false))
        val action = recovery.prepareCommit(userA, draft(), choices)
        choices[1] = ImportRowChoice(false)
        choices[2] = ImportRowChoice(true)
        // Simulate response loss/process recreation: no resolution callback ran.
        val resumed = ImportRecoveryCoordinator(storage).pending(userA)!!
        assertEquals(action.key, resumed.key)
        assertEquals(listOf(1), resumed.selection!!.rows.map { it.rowId })
        assertTrue(resumed.selection.rows.single().excludeFromAnalytics)
        assertThrows(IllegalArgumentException::class.java) { recovery.prepareCommit(userA, draft(), choices) }
        assertEquals(action, recovery.pending(userA))
    }

    @Test fun `possible duplicates require explicit optin and exact duplicates are blocked`() {
        val possible = draft().copy(rows = draft().rows.take(1).map { it.copy(duplicate = "possible", matchingCount = 1) })
        assertThrows(IllegalArgumentException::class.java) {
            ImportRecoveryCoordinator(MemoryStore()).prepareCommit(userA, possible, mapOf(1 to ImportRowChoice(true)))
        }
        val accepted = ImportRecoveryCoordinator(MemoryStore()).prepareCommit(userA, possible,
            mapOf(1 to ImportRowChoice(true, allowPossibleDuplicate = true)))
        assertTrue(accepted.selection!!.rows.single().allowPossibleDuplicate)
        assertThrows(IllegalArgumentException::class.java) {
            ImportRecoveryCoordinator(MemoryStore()).prepareCommit(userA, possible.copy(rows = possible.rows.map { it.copy(duplicate = "exact") }),
                mapOf(1 to ImportRowChoice(true, allowPossibleDuplicate = true)))
        }
    }

    @Test fun `recovery is isolated by user and resolved only by matching key`() {
        val storage = MemoryStore(); val recovery = ImportRecoveryCoordinator(storage)
        val a = recovery.prepareCommit(userA, draft(), mapOf(1 to ImportRowChoice(true)))
        val b = recovery.prepareCommit(userB, draft(), mapOf(2 to ImportRowChoice(true)))
        assertEquals(a, recovery.pending(userA)); assertEquals(b, recovery.pending(userB))
        storage.clear(userA, b.key); assertEquals(a, recovery.pending(userA))
        recovery.resolved(a); assertNull(recovery.pending(userA)); assertEquals(b, recovery.pending(userB))
    }

    @Test fun `only definitive failures allow new review while ambiguous results stay frozen`() {
        assertTrue(definitiveImportFailure(409, "import_preview_changed"))
        assertTrue(definitiveImportFailure(410, "import_expired"))
        assertFalse(definitiveImportFailure(409, "import_already_committed"))
        assertFalse(definitiveImportFailure(409, "idempotency_conflict"))
        listOf(401, 408, 429, 500, 503).forEach { assertFalse(definitiveImportFailure(it, null)) }
    }

    @Test fun `authenticated undone response resolves old uncertain commit without recreating it`() {
        val recovery = ImportRecoveryCoordinator(MemoryStore())
        val action = recovery.prepareCommit(userA, draft(), mapOf(1 to ImportRowChoice(true)))
        assertTrue(authoritativeUndoneImport(409, "import_undone"))
        assertFalse(authoritativeUndoneImport(500, "import_undone"))
        assertFalse(authoritativeUndoneImport(409, "import_preview_changed"))
        // Another device undid the committed draft. Resolve its original key; never prepare a replacement commit.
        if (authoritativeUndoneImport(409, "import_undone")) recovery.resolved(action)
        assertNull(recovery.pending(userA))
        val cancelled = ImportReceiptDto(action.draftId, action.accountId, "kaspi", "undone", 1, 1, "0",
            "2026-01-01T00:00:00Z", "2026-01-01T00:00:01Z")
        assertThrows(IllegalArgumentException::class.java) { recovery.prepareUndo(userA, cancelled) }
        assertNull(recovery.pending(userA))
    }

    private class MemoryStore : ImportRecoveryStorage {
        private val values = mutableMapOf<String, PendingImportAction>()
        override fun read(userId: String) = values[userId]
        override fun write(action: PendingImportAction) { values[action.userId] = action }
        override fun clear(userId: String, expectedKey: String) { if (values[userId]?.key == expectedKey) values.remove(userId) }
    }
}
