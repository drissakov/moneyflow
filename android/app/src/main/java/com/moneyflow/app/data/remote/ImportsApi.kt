package com.moneyflow.app.data.remote

import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.ZoneId
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

data class ImportSourceRow(
    @SerializedName("row_id") val rowId: Int,
    @SerializedName("occurred_at") val occurredAt: String,
    val kind: String,
    @SerializedName("amount_minor") val amountMinor: String,
    val note: String,
)

data class ImportPreviewRequest(
    @SerializedName("account_id") val accountId: String,
    val bank: String,
    val currency: String,
    @SerializedName("file_sha256") val fileSha256: String,
    val timezone: String,
    @SerializedName("opening_balance_minor") val openingBalanceMinor: String?,
    @SerializedName("closing_balance_minor") val closingBalanceMinor: String?,
    val rows: List<ImportSourceRow>,
) {
    fun validate() {
        validateUuid(accountId); require(bank in setOf("kaspi", "halyk") && currency == "KZT")
        require(fileSha256.matches(Regex("[0-9a-f]{64}")))
        ZoneId.of(timezone)
        openingBalanceMinor?.let { parseServerMinor(it) }; closingBalanceMinor?.let { parseServerMinor(it) }
        require(rows.size in 1..2000 && rows.map { it.rowId }.distinct().size == rows.size)
        rows.forEach { validateImportRow(it.rowId, it.occurredAt, it.kind, it.amountMinor, it.note) }
    }
}

data class ImportPreviewRow(
    @SerializedName("row_id") val rowId: Int,
    @SerializedName("occurred_at") val occurredAt: String,
    val kind: String,
    @SerializedName("amount_minor") val amountMinor: String,
    val note: String,
    val duplicate: String,
    @SerializedName("matching_count") val matchingCount: Int,
    @SerializedName("suggested_category_id") val suggestedCategoryId: String? = null,
    @SerializedName("category_suggestion_source") val categorySuggestionSource: String? = null,
    @SerializedName("category_match_key") val categoryMatchKey: String? = null,
)

data class ImportPreviewDto(
    val id: String,
    @SerializedName("account_id") val accountId: String,
    val bank: String,
    val currency: String,
    val status: String,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("balance_before_minor") val balanceBeforeMinor: String,
    @SerializedName("opening_balance_minor") val openingBalanceMinor: String?,
    @SerializedName("closing_balance_minor") val closingBalanceMinor: String?,
    val rows: List<ImportPreviewRow>,
) {
    fun validated(request: ImportPreviewRequest): ImportPreviewDto = apply {
        validateUuid(id); validateUuid(accountId)
        require(accountId == request.accountId && bank == request.bank && currency == request.currency && status == "draft")
        Instant.parse(expiresAt); parseServerMinor(balanceBeforeMinor)
        require(openingBalanceMinor == request.openingBalanceMinor && closingBalanceMinor == request.closingBalanceMinor)
        require(rows.size == request.rows.size && rows.map { it.rowId }.distinct().size == rows.size)
        val original = request.rows.associateBy { it.rowId }
        rows.forEach {
            validateImportRow(it.rowId, it.occurredAt, it.kind, it.amountMinor, it.note)
            val source = requireNotNull(original[it.rowId])
            require(it.kind == source.kind && it.amountMinor == source.amountMinor && it.note == source.note &&
                Instant.parse(it.occurredAt) == Instant.parse(source.occurredAt))
            require(it.duplicate in setOf("new", "possible", "exact") && it.matchingCount >= 0)
            require(it.duplicate != "new" || it.matchingCount == 0)
            require(it.duplicate != "possible" || it.matchingCount > 0)
            it.suggestedCategoryId?.let(::validateUuid)
            require((it.suggestedCategoryId == null) == (it.categorySuggestionSource == null))
            require(it.categorySuggestionSource == null || it.categorySuggestionSource in setOf("history", "rule"))
            require(it.categoryMatchKey == null || it.categoryMatchKey.matches(Regex("v1:[0-9a-f]{64}")))
        }
    }
}

data class ImportSelectionRow(
    @SerializedName("row_id") val rowId: Int,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("allow_possible_duplicate") val allowPossibleDuplicate: Boolean,
    @SerializedName("exclude_from_analytics") val excludeFromAnalytics: Boolean = false,
)

data class ImportCommitRequest(val rows: List<ImportSelectionRow>) {
    fun validate() {
        require(rows.size in 1..2000 && rows.map { it.rowId }.distinct().size == rows.size)
        rows.forEach { require(it.rowId > 0); it.categoryId?.let(::validateUuid) }
    }
}

data class ImportReceiptDto(
    val id: String,
    @SerializedName("account_id") val accountId: String,
    val bank: String,
    val status: String,
    @SerializedName("imported_count") val importedCount: Int,
    @SerializedName("skipped_count") val skippedCount: Int,
    @SerializedName("balance_minor") val balanceMinor: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("committed_at") val committedAt: String?,
    @SerializedName("undone_at") val undoneAt: String? = null,
) {
    fun validated(): ImportReceiptDto = apply {
        validateUuid(id); validateUuid(accountId)
        require(bank in setOf("kaspi", "halyk") && status in setOf("draft", "committed", "undone"))
        require(importedCount >= 0 && skippedCount >= 0 && importedCount.toLong() + skippedCount <= 2000)
        parseServerMinor(balanceMinor); Instant.parse(createdAt)
        committedAt?.let { Instant.parse(it) }; undoneAt?.let { Instant.parse(it) }
        if (status != "draft") requireNotNull(committedAt)
    }
}
data class ImportsHistoryDto(val imports: List<ImportReceiptDto>) {
    fun validated(): ImportsHistoryDto = apply {
        require(imports.map { it.id }.distinct().size == imports.size)
        imports.forEach { it.validated() }
    }
}

/** Only immutable normalized selection/key recovery metadata is saved locally, never a PDF or its extracted text. */
data class PendingImportAction(
    val userId: String, val accountId: String, val draftId: String, val key: String,
    val operation: String, val selection: ImportCommitRequest?, val totalRows: Int,
    val originalImportedCount: Int, val createdAt: String,
) {
    fun validate() {
        listOf(userId, accountId, draftId, key).forEach(::validateUuid)
        require(operation in setOf("commit", "undo"))
        require(totalRows in 0..2000 && originalImportedCount in 0..2000)
        Instant.parse(createdAt)
        if (operation == "commit") { requireNotNull(selection).validate(); require(selection.rows.size <= totalRows) }
        else require(selection == null)
    }
}

interface ImportsApi {
    @POST("api/v1/imports/preview") suspend fun preview(@Body body: ImportPreviewRequest,
        @Header("Authorization") authorization: String): ImportPreviewDto
    @POST("api/v1/imports/{id}/commit") suspend fun commit(@Path("id") id: String, @Body body: ImportCommitRequest,
        @Header("Idempotency-Key") key: String, @Header("Authorization") authorization: String): ImportReceiptDto
    @GET("api/v1/imports") suspend fun history(@Query("limit") limit: Int = 20,
        @Header("Authorization") authorization: String): ImportsHistoryDto
    @POST("api/v1/imports/{id}/undo") suspend fun undo(@Path("id") id: String, @Body body: Map<String, String> = emptyMap(),
        @Header("Idempotency-Key") key: String, @Header("Authorization") authorization: String): ImportReceiptDto
}

private fun validateImportRow(id: Int, occurredAt: String, kind: String, amountMinor: String, note: String) {
    require(id > 0 && kind in setOf("expense", "income"))
    Instant.parse(occurredAt); parseServerMinor(amountMinor, positive = true, bounded = true)
    require(note.codePointCount(0, note.length) <= 500)
}
