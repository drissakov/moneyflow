package com.moneyflow.app.data.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.moneyflow.app.data.domain.MoneyTransfer
import com.moneyflow.app.data.domain.SupportedCurrency
import com.moneyflow.app.data.domain.PlanningCalendar
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
    val currency: String? = null,
    val analyticsOnly: Boolean = false,
    val uncategorized: Boolean = false,
    val categoryExact: Boolean = false,
) {
    fun validate() {
        accountId?.let(::validateUuid); categoryId?.let(::validateUuid)
        currency?.let { SupportedCurrency.fromCode(it) }
        require(!uncategorized || categoryId == null) { "Выберите категорию или операции без категории" }
        require(!categoryExact || categoryId != null) { "Для точного фильтра выберите категорию" }
        require(!analyticsOnly || !showDeleted) { "Удалённые операции не входят в отчёт" }
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
    val entityId: String?, val body: String, val occurrenceDate: String? = null) {
    fun validate() {
        validateUuid(userId); validateUuid(key); entityId?.let(::validateUuid)
        require(operation in OPERATIONS)
        require(body.toByteArray(Charsets.UTF_8).size in 2..16_384)
        val json = json()
        val create = operation.startsWith("create")
        val occurrence = operation in OCCURRENCE_OPERATIONS
        require((create || operation == "editplanningsettings") == (entityId == null))
        require(occurrence == (occurrenceDate != null))
        if (occurrence) {
            PlanningCalendar.validateDate(requireNotNull(occurrenceDate))
            require(json["expected_template_version"].asLong >= 1)
            require(json["expected_occurrence_version"].asLong >= 0)
        } else if (operation == "editplanningsettings") require(json["expected_version"].asLong >= 0)
        else if (!create) require(json["expected_version"].asLong >= 1)
        if (operation in PLANNING_OPERATIONS) validatePlanningMutationBody(operation, json)
    }
    fun json(): JsonObject = JsonParser.parseString(body).asJsonObject
    val path: String get() = "api/v1/" + when (operation) {
        "createaccount", "editaccount" -> "accounts" + (entityId?.let { "/$it" } ?: "")
        "createcategory", "editcategory", "mergecategory" -> "categories" + (entityId?.let { "/$it" } ?: "") + if (operation == "mergecategory") "/merge" else ""
        "edittransaction", "deletetransaction", "restoretransaction" -> "transactions/$entityId" + if (operation == "restoretransaction") "/restore" else ""
        "createtransfer", "edittransfer", "deletetransfer", "restoretransfer" -> "transfers" + (entityId?.let { "/$it" } ?: "") + if (operation == "restoretransfer") "/restore" else ""
        "createbudget", "editbudget" -> "budgets" + (entityId?.let { "/$it" } ?: "")
        "createschedule", "editschedule" -> "schedules" + (entityId?.let { "/$it" } ?: "")
        "skipoccurrence" -> "schedules/$entityId/occurrences/$occurrenceDate/skip"
        "unskipoccurrence" -> "schedules/$entityId/occurrences/$occurrenceDate/unskip"
        "postoccurrence" -> "schedules/$entityId/occurrences/$occurrenceDate/post"
        "editplanningsettings" -> "planning/settings"
        else -> throw IllegalArgumentException("Неизвестный тип подтверждения")
    }
    val method: String get() = when (operation) {
        "deletetransaction", "deletetransfer" -> "DELETE"
        "editaccount", "editcategory", "edittransaction", "edittransfer", "editbudget", "editschedule", "editplanningsettings" -> "PUT"
        "createaccount", "createcategory", "mergecategory", "restoretransaction", "createtransfer", "restoretransfer",
        "createbudget", "createschedule", "skipoccurrence", "unskipoccurrence", "postoccurrence" -> "POST"
        else -> throw IllegalArgumentException("Неизвестный тип подтверждения")
    }
    companion object {
        val OCCURRENCE_OPERATIONS = setOf("skipoccurrence", "unskipoccurrence", "postoccurrence")
        val PLANNING_OPERATIONS = setOf("createbudget", "editbudget", "createschedule", "editschedule", "editplanningsettings") + OCCURRENCE_OPERATIONS
        val OPERATIONS = setOf("createaccount", "editaccount", "createcategory", "editcategory", "mergecategory",
            "edittransaction", "deletetransaction", "restoretransaction", "createtransfer", "edittransfer", "deletetransfer", "restoretransfer") + PLANNING_OPERATIONS
    }
}
