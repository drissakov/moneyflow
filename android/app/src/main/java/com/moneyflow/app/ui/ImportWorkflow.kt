package com.moneyflow.app.ui

import com.moneyflow.app.data.ImportRecoveryStorage
import com.moneyflow.app.data.remote.*
import java.time.Instant
import java.util.UUID

data class ImportRowChoice(
    val selected: Boolean,
    val categoryId: String? = null,
    val allowPossibleDuplicate: Boolean = false,
    val excludeFromAnalytics: Boolean = false,
    val transferReviewed: Boolean = false,
)

/** Makes the exact submitted confirmation immutable until its outcome is known. */
class ImportRecoveryCoordinator(private val storage: ImportRecoveryStorage) {
    fun pending(userId: String): PendingImportAction? = storage.read(userId)
    fun prepareCommit(userId: String, draft: ImportPreviewDto, choices: Map<Int, ImportRowChoice>): PendingImportAction {
        require(storage.read(userId) == null) { "Сначала завершите ожидающее подтверждение" }
        val rows = draft.rows.filter { choices[it.rowId]?.selected == true }.map { row ->
            val choice = requireNotNull(choices[row.rowId])
            require(row.duplicate != "exact") { "Уже импортированная запись не может быть выбрана" }
            require(row.duplicate != "possible" || choice.allowPossibleDuplicate) { "Подтвердите возможные повторы" }
            ImportSelectionRow(row.rowId, choice.categoryId, choice.allowPossibleDuplicate, choice.excludeFromAnalytics)
        }.sortedBy { it.rowId }
        val action = PendingImportAction(userId, draft.accountId, draft.id, UUID.randomUUID().toString(),
            "commit", ImportCommitRequest(rows), draft.rows.size, 0, Instant.now().toString())
        action.validate(); storage.write(action)
        return action
    }
    fun prepareUndo(userId: String, receipt: ImportReceiptDto): PendingImportAction {
        require(storage.read(userId) == null && receipt.status == "committed") { "Сначала завершите ожидающее подтверждение" }
        val action = PendingImportAction(userId, receipt.accountId, receipt.id, UUID.randomUUID().toString(), "undo", null,
            receipt.importedCount + receipt.skippedCount, receipt.importedCount, Instant.now().toString())
        action.validate(); storage.write(action)
        return action
    }
    fun resolved(action: PendingImportAction) = storage.clear(action.userId, action.key)
    fun forgetAfterHistoryReview(userId: String, confirmed: Boolean) {
        require(confirmed) { "Сначала проверьте историю" }
        storage.forgetAfterReview(userId)
    }
}

internal fun definitiveImportFailure(status: Int, code: String?): Boolean =
    status in setOf(400, 403, 404, 410, 413, 415, 422) || (status == 409 && code in setOf(
        "import_duplicate_exact", "import_duplicate_possible", "import_preview_changed", "balance_overflow", "import_not_committed"))

internal fun authoritativeUndoneImport(status: Int, code: String?): Boolean = status == 409 && code == "import_undone"
