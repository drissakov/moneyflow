package com.moneyflow.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moneyflow.app.data.remote.ImportPreviewDto
import com.moneyflow.app.data.remote.ImportPreviewRow
import com.moneyflow.app.data.remote.ImportCommitRequest
import com.moneyflow.app.data.remote.ImportSelectionRow
import com.moneyflow.app.data.remote.PendingImportAction
import com.moneyflow.app.ui.ImportRecoveryCoordinator
import com.moneyflow.app.ui.ImportRowChoice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ImportRecoveryStorageTest {
    @Test fun transientDecryptionFailureRetainsTheEncryptedConfirmationAndBlocksNewFinancialConfirmation() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("import_recovery_test_$name", mode)
        }
        val preferences = context.getSharedPreferences("moneyflow_import_recovery", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val user = "784b38ac-d188-491f-92c5-bb173e57f6a4"
        val account = "784b38ac-d188-491f-92c5-bb173e57f6a5"
        val draft = "784b38ac-d188-491f-92c5-bb173e57f6a6"
        val key = "784b38ac-d188-491f-92c5-bb173e57f6a7"
        val action = PendingImportAction(user, account, draft, key, "commit",
            ImportCommitRequest(listOf(ImportSelectionRow(1, null, false))), 1, 0, Instant.now().toString())
        try {
            val healthy = EncryptedImportRecoveryStore(context)
            healthy.write(action)
            val encryptedBefore = preferences.getString("pending_$user", null)!!
            val failing = EncryptedImportRecoveryStore(context) { _, _ -> throw IOException("Synthetic temporary key-provider failure") }
            assertThrows(ImportRecoveryUnavailable::class.java) { failing.read(user) }
            val preview = ImportPreviewDto(draft, account, "kaspi", "KZT", "draft", Instant.now().plusSeconds(3600).toString(),
                "0", "0", "-100", listOf(ImportPreviewRow(1, "2026-01-01T00:00:00Z", "expense", "100", "Example", "new", 0)))
            assertThrows(ImportRecoveryUnavailable::class.java) {
                ImportRecoveryCoordinator(failing).prepareCommit(user, preview, mapOf(1 to ImportRowChoice(true)))
            }
            assertEquals(encryptedBefore, preferences.getString("pending_$user", null))
            assertEquals(action, healthy.read(user)) // The original key remains usable after a transient failure ends.
            assertThrows(IllegalArgumentException::class.java) { ImportRecoveryCoordinator(failing).forgetAfterHistoryReview(user, false) }
            assertEquals(encryptedBefore, preferences.getString("pending_$user", null))
        } finally { preferences.edit().clear().commit() }
    }
}
