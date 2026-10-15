package com.moneyflow.app.data.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.moneyflow.app.data.domain.MoneyTransfer
import com.moneyflow.app.data.domain.SupportedCurrency
import java.time.Instant

/** Inclusive from, exclusive to. The opaque cursor belongs only to this exact filter. */
data class LedgerFilter(
    val accountId: String? = null,
    val categoryId: String? = null,
    val kind: String? = null,
    val query: String = "",
    val from: String? = null,
    val to: String? = null,
    val showDeleted: Boolean = false,
) {
    fun validate() {
        accountId?.let(::validateUuid); categoryId?.let(::validateUuid)
        require(kind == null || kind in setOf("expense", "income"))
        require(query.codePointCount(0, query.length) <= 100) { "Поиск: не больше 100 символов" }
        val start = from?.let(Instant::parse); val end = to?.let(Instant::parse)
        require(start == null || end == null || start < end) { "Начало периода должно быть раньше окончания" }
    }
}

data class TransferDto(
    val id: String,
    @SerializedName("from_account_id") val fromAccountId: String,
    @SerializedName("to_account_id") val toAccountId: String,
    @SerializedName("from_amount_minor") val fromAmountMinor: String,
    @SerializedName("to_amount_minor") val toAmountMinor: String,
    @SerializedName("from_currency") val fromCurrency: String,
    @SerializedName("to_currency") val toCurrency: String,
    @SerializedName("fee_account_id") val feeAccountId: String?,
    @SerializedName("fee_amount_minor") val feeAmountMinor: String,
    @SerializedName("fee_currency") val feeCurrency: String?,
    val note: String,
    @SerializedName("occurred_at") val occurredAt: String,
    @SerializedName("created_at") val createdAt: String,
    val version: Long,
    @SerializedName("deleted_at") val deletedAt: String?,
    val transactions: List<TransactionDto>,
) {
    fun toDomain(): MoneyTransfer {
        validateUuid(id); validateUuid(fromAccountId); validateUuid(toAccountId)
        require(fromAccountId != toAccountId)
        SupportedCurrency.fromCode(fromCurrency); SupportedCurrency.fromCode(toCurrency)
        val fromAmount = parseServerMinor(fromAmountMinor, positive = true, bounded = true)
        val toAmount = parseServerMinor(toAmountMinor, positive = true, bounded = true)
        if (fromCurrency == toCurrency) require(fromAmount == toAmount)
        val fee = parseServerMinor(feeAmountMinor, bounded = true); require(fee >= 0)
        feeAccountId?.let(::validateUuid); feeCurrency?.let { SupportedCurrency.fromCode(it) }
        require(fee == 0L || (feeAccountId != null && feeCurrency != null))
        require(note.codePointCount(0, note.length) <= 500)
        Instant.parse(occurredAt); Instant.parse(createdAt); deletedAt?.let(Instant::parse)
        require(version >= 1)
        val rows = transactions.map(TransactionDto::toDomain)
        require(rows.map { it.id }.distinct().size == rows.size)
        require(rows.size == if (fee > 0) 3 else 2)
        rows.forEach { require(it.transferId == id && it.source == "transfer" && (it.deletedAt != null) == (deletedAt != null)) }
        val outgoing = rows.single { it.transferRole == "from" }; val incoming = rows.single { it.transferRole == "to" }
        require(outgoing.accountId == fromAccountId && outgoing.amountMinor == fromAmount && outgoing.kind == "expense" &&
            outgoing.currency == fromCurrency && outgoing.excludeFromAnalytics)
        require(incoming.accountId == toAccountId && incoming.amountMinor == toAmount && incoming.kind == "income" &&
            incoming.currency == toCurrency && incoming.excludeFromAnalytics)
        if (fee > 0) rows.single { it.transferRole == "fee" }.let {
            require(it.accountId == feeAccountId && it.currency == feeCurrency && it.amountMinor == fee && it.kind == "expense" && !it.excludeFromAnalytics)
        }
        return MoneyTransfer(id, fromAccountId, toAccountId, fromAmount, toAmount, fromCurrency, toCurrency,
            feeAccountId, fee, feeCurrency, note, occurredAt, createdAt, version, deletedAt, rows)
    }
}

/** The exact UTF-8 JSON body and key are immutable until acknowledged or definitively rejected. */
data class PendingLedgerMutation(val userId: String, val key: String, val operation: String,
    val entityId: String?, val body: String) {
    fun validate() {
        validateUuid(userId); validateUuid(key); entityId?.let(::validateUuid)
        require(operation in OPERATIONS)
        require(body.toByteArray(Charsets.UTF_8).size in 2..16_384)
        val json = json()
        val create = operation.startsWith("create")
        require(create == (entityId == null))
        if (!create) require(json["expected_version"].asLong >= 1)
    }
    fun json(): JsonObject = JsonParser.parseString(body).asJsonObject
    val path: String get() = "api/v1/" + when {
        operation.endsWith("account") -> "accounts" + (entityId?.let { "/$it" } ?: "")
        operation.endsWith("category") -> "categories" + (entityId?.let { "/$it" } ?: "") + if (operation == "mergecategory") "/merge" else ""
        operation.endsWith("transaction") -> "transactions/$entityId" + if (operation == "restoretransaction") "/restore" else ""
        else -> "transfers" + (entityId?.let { "/$it" } ?: "") + if (operation == "restoretransfer") "/restore" else ""
    }
    val method: String get() = when {
        operation.startsWith("delete") -> "DELETE"
        operation.startsWith("edit") -> "PUT"
        else -> "POST"
    }
    companion object {
        val OPERATIONS = setOf("createaccount", "editaccount", "createcategory", "editcategory", "mergecategory",
            "edittransaction", "deletetransaction", "restoretransaction", "createtransfer", "edittransfer", "deletetransfer", "restoretransfer")
    }
}
