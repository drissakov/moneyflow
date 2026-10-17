package com.moneyflow.app.data.remote

import com.google.gson.annotations.SerializedName
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.moneyflow.app.data.domain.PlanningCalendar
import com.moneyflow.app.data.domain.SupportedCurrency
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.ChronoUnit

data class BudgetDto(
    val id: String, val name: String,
    @SerializedName("category_id") val categoryId: String,
    val currency: String,
    @SerializedName("limit_minor") val limitMinor: String,
    @SerializedName("period_start_day") val periodStartDay: Int,
    val archived: Boolean, val version: Long,
    @SerializedName("period_from") val periodFrom: String,
    @SerializedName("period_to") val periodTo: String,
    @SerializedName("spent_minor") val spentMinor: String,
    @SerializedName("remaining_minor") val remainingMinor: String,
    @SerializedName("category_archived") val categoryArchived: Boolean,
    @SerializedName("category_name") val categoryName: String,
) {
    fun validated(): BudgetDto = apply {
        validateUuid(id); validateUuid(categoryId); planningName(name); planningName(categoryName)
        SupportedCurrency.fromCode(currency); parseServerMinor(limitMinor, positive = true, bounded = true)
        require(periodStartDay in 1..31 && version >= 1)
        require(PlanningCalendar.validateDate(periodFrom) < PlanningCalendar.validateDate(periodTo))
        require(parseServerMinor(spentMinor) >= 0)
        parseServerMinor(remainingMinor)
        require(BigInteger(limitMinor) - BigInteger(spentMinor) == BigInteger(remainingMinor))
    }
}

data class ScheduleDto(
    val id: String, val name: String,
    @SerializedName("account_id") val accountId: String,
    @SerializedName("category_id") val categoryId: String?,
    val kind: String, val currency: String,
    @SerializedName("amount_minor") val amountMinor: String,
    val note: String,
    @SerializedName("start_date") val startDate: String,
    @SerializedName("end_date") val endDate: String?,
    val frequency: String, val interval: Int, val mandatory: Boolean,
    @SerializedName("primary_income") val primaryIncome: Boolean,
    val paused: Boolean, val archived: Boolean, val version: Long,
    @SerializedName("blocked_reason") val blockedReason: String?,
    @SerializedName("schedule_locked") val scheduleLocked: Boolean = false,
) {
    fun validated(): ScheduleDto = apply {
        validateUuid(id); planningEntry(name, accountId, categoryId, kind, currency, amountMinor, note, mandatory, primaryIncome)
        val start = PlanningCalendar.validateDate(startDate)
        endDate?.let { require(PlanningCalendar.validateDate(it) >= start) }
        require(frequency in PLANNING_FREQUENCIES && interval in 1..12 && version >= 1)
        planningBlock(blockedReason)
    }
}

data class OccurrenceDto(
    @SerializedName("schedule_id") val scheduleId: String,
    @SerializedName("schedule_version") val scheduleVersion: Long,
    @SerializedName("due_date") val dueDate: String,
    val version: Long, val name: String,
    @SerializedName("account_id") val accountId: String,
    @SerializedName("category_id") val categoryId: String?,
    val kind: String, val currency: String,
    @SerializedName("amount_minor") val amountMinor: String,
    val note: String, val mandatory: Boolean,
    @SerializedName("primary_income") val primaryIncome: Boolean,
    val status: String,
    @SerializedName("transaction_id") val transactionId: String?,
    @SerializedName("transaction_deleted") val transactionDeleted: Boolean,
    @SerializedName("blocked_reason") val blockedReason: String?,
) {
    fun validated(): OccurrenceDto = apply {
        validateUuid(scheduleId); require(scheduleVersion >= 1 && version >= 0); PlanningCalendar.validateDate(dueDate)
        planningEntry(name, accountId, categoryId, kind, currency, amountMinor, note, mandatory, primaryIncome)
        require(status in setOf("pending", "skipped", "posted")); transactionId?.let(::validateUuid)
        require((status == "posted") == (transactionId != null))
        require(status == "pending" || version >= 1)
        require(!transactionDeleted || status == "posted")
        planningBlock(blockedReason)
    }
}

data class PlanningSettingsDto(
    val version: Long,
    @SerializedName("reserve_minor") val reserveMinor: String,
    @SerializedName("account_ids") val accountIds: List<String>,
    @SerializedName("base_currency") val baseCurrency: String,
) {
    fun validated(): PlanningSettingsDto = apply {
        require(version >= 0); require(parseServerMinor(reserveMinor, bounded = true) >= 0)
        SupportedCurrency.fromCode(baseCurrency)
        require(accountIds.size <= 200 && accountIds.distinct().size == accountIds.size); accountIds.forEach(::validateUuid)
    }
}

data class PlanningResponseDto(
    val today: String, val timezone: String, val from: String, val to: String,
    @SerializedName("overdue_from") val overdueFrom: String,
    @SerializedName("timeline_truncated") val timelineTruncated: Boolean,
    @SerializedName("overdue_count") val overdueCount: Int,
    val budgets: List<BudgetDto>, val schedules: List<ScheduleDto>, val occurrences: List<OccurrenceDto>,
    val settings: PlanningSettingsDto,
) {
    fun validated(expectedFrom: String? = null, expectedTo: String? = null): PlanningResponseDto = apply {
        val now = PlanningCalendar.validateDate(today); val start = PlanningCalendar.validateDate(from); val end = PlanningCalendar.validateDate(to)
        require(timezone == "UTC+05:00" && start <= end && ChronoUnit.DAYS.between(start, end) <= 365)
        require(PlanningCalendar.validateDate(overdueFrom) <= now && overdueCount >= 0)
        require(expectedFrom == null || expectedFrom == from); require(expectedTo == null || expectedTo == to)
        require(budgets.size <= 200 && schedules.size <= 200 && occurrences.size <= 1_000)
        require(budgets.map { it.id }.distinct().size == budgets.size)
        require(schedules.map { it.id }.distinct().size == schedules.size)
        require(occurrences.map { it.scheduleId to it.dueDate }.distinct().size == occurrences.size)
        budgets.forEach { it.validated() }; schedules.forEach { it.validated() }; occurrences.forEach { it.validated() }; settings.validated()
        val scheduleIds = schedules.map { it.id }.toSet()
        occurrences.forEach { require(it.scheduleId in scheduleIds) }
    }
}

data class ForecastAccountDto(
    val id: String, val name: String, val currency: String,
    @SerializedName("balance_minor") val balanceMinor: String,
    @SerializedName("converted_balance_minor") val convertedBalanceMinor: String?,
) {
    fun validated(base: String): ForecastAccountDto = apply {
        validateUuid(id); planningName(name); SupportedCurrency.fromCode(currency); parseServerMinor(balanceMinor)
        convertedBalanceMinor?.let(::parseServerMinor)
        if (currency == base) require(convertedBalanceMinor == balanceMinor)
    }
}

data class ForecastObligationDto(
    @SerializedName("schedule_id") val scheduleId: String,
    @SerializedName("schedule_version") val scheduleVersion: Long,
    @SerializedName("due_date") val dueDate: String,
    val version: Long, val name: String,
    @SerializedName("account_id") val accountId: String,
    @SerializedName("category_id") val categoryId: String?,
    val kind: String, val currency: String,
    @SerializedName("amount_minor") val amountMinor: String,
    val note: String, val mandatory: Boolean,
    @SerializedName("primary_income") val primaryIncome: Boolean,
    val status: String,
    @SerializedName("transaction_id") val transactionId: String?,
    @SerializedName("transaction_deleted") val transactionDeleted: Boolean,
    @SerializedName("blocked_reason") val blockedReason: String?,
    @SerializedName("converted_amount_minor") val convertedAmountMinor: String?,
) {
    fun toOccurrence() = OccurrenceDto(scheduleId, scheduleVersion, dueDate, version, name, accountId, categoryId,
        kind, currency, amountMinor, note, mandatory, primaryIncome, status, transactionId, transactionDeleted, blockedReason)
    fun validated(base: String): ForecastObligationDto = apply {
        toOccurrence().validated(); require(kind == "expense" && mandatory && status == "pending")
        convertedAmountMinor?.let { require(parseServerMinor(it) >= 0) }
        if (currency == base) require(convertedAmountMinor == amountMinor)
    }
}

data class ForecastResponseDto(
    @SerializedName("as_of") val asOf: String,
    val until: String,
    @SerializedName("horizon_reason") val horizonReason: String,
    @SerializedName("base_currency") val baseCurrency: String,
    val complete: Boolean, val stale: Boolean,
    @SerializedName("fetched_at") val fetchedAt: String?,
    @SerializedName("missing_currencies") val missingCurrencies: List<String>,
    val accounts: List<ForecastAccountDto>, val obligations: List<ForecastObligationDto>,
    @SerializedName("balance_minor") val balanceMinor: String?,
    @SerializedName("reserve_minor") val reserveMinor: String,
    @SerializedName("mandatory_minor") val mandatoryMinor: String?,
    @SerializedName("free_minor") val freeMinor: String?,
    @SerializedName("per_day_minor") val perDayMinor: String?,
    val days: Int,
    @SerializedName("overdue_from") val overdueFrom: String,
    @SerializedName("timeline_truncated") val timelineTruncated: Boolean,
) {
    fun validated(expectedUntil: String? = null): ForecastResponseDto = apply {
        val start = PlanningCalendar.validateDate(asOf); val end = PlanningCalendar.validateDate(until)
        require(end >= start && days in 1..366 && days.toLong() == ChronoUnit.DAYS.between(start, end) + 1)
        require(expectedUntil == null || expectedUntil == until)
        require(horizonReason in setOf("primary_income", "month_end", "custom")); SupportedCurrency.fromCode(baseCurrency)
        require(PlanningCalendar.validateDate(overdueFrom) <= start)
        fetchedAt?.let(Instant::parse); require(!stale || fetchedAt != null)
        require(missingCurrencies.distinct().size == missingCurrencies.size); missingCurrencies.forEach { SupportedCurrency.fromCode(it) }
        require(accounts.map { it.id }.distinct().size == accounts.size)
        require(obligations.map { it.scheduleId to it.dueDate }.distinct().size == obligations.size)
        accounts.forEach { it.validated(baseCurrency) }; obligations.forEach { it.validated(baseCurrency) }
        require(parseServerMinor(reserveMinor, bounded = true) >= 0)
        val missing = accounts.filter { it.convertedBalanceMinor == null }.map { it.currency }.toSet() +
            obligations.filter { it.convertedAmountMinor == null }.map { it.currency }.toSet()
        require(missing == missingCurrencies.toSet())
        require(complete == (missing.isEmpty() && !timelineTruncated))
        if (complete) {
            val balance = BigInteger(requireNotNull(balanceMinor).also(::parseServerMinor))
            val mandatory = BigInteger(requireNotNull(mandatoryMinor).also(::parseServerMinor))
            val free = BigInteger(requireNotNull(freeMinor).also(::parseServerMinor))
            val daily = BigInteger(requireNotNull(perDayMinor).also(::parseServerMinor))
            require(balance == accounts.fold(BigInteger.ZERO) { sum, account -> sum + BigInteger(requireNotNull(account.convertedBalanceMinor)) })
            require(mandatory == obligations.fold(BigInteger.ZERO) { sum, occurrence -> sum + BigInteger(requireNotNull(occurrence.convertedAmountMinor)) })
            require(free == balance - BigInteger(reserveMinor) - mandatory)
            val parts = free.divideAndRemainder(BigInteger.valueOf(days.toLong()))
            val floor = parts[0] - if (free.signum() < 0 && parts[1] != BigInteger.ZERO) BigInteger.ONE else BigInteger.ZERO
            require(daily == floor)
        } else require(balanceMinor == null && mandatoryMinor == null && freeMinor == null && perDayMinor == null)
    }
}

internal val PLANNING_FREQUENCIES = setOf("once", "weekly", "monthly", "yearly")
private fun planningName(value: String) { require(value.codePointCount(0, value.length) in 1..100) }
private fun planningBlock(value: String?) { require(value == null || value in setOf("archived_account", "archived_category", "paused", "archived_schedule")) }
private fun planningEntry(name: String, accountId: String, categoryId: String?, kind: String, currency: String,
    amount: String, note: String, mandatory: Boolean, primaryIncome: Boolean) {
    planningName(name); validateUuid(accountId); categoryId?.let(::validateUuid)
    require(kind in setOf("expense", "income")); SupportedCurrency.fromCode(currency)
    parseServerMinor(amount, positive = true, bounded = true)
    require(note.codePointCount(0, note.length) <= 500)
    require(!mandatory || kind == "expense"); require(!primaryIncome || kind == "income")
}

/** Validate restored payloads as strictly as a newly submitted form. Routing data stays outside the body. */
internal fun validatePlanningMutationBody(operation: String, body: JsonObject) {
    fun text(key: String): String = body[key].also { require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString
    fun nullableId(key: String): String? = body[key].also { require(it != null) }.takeUnless { it.isJsonNull }?.asString?.also(::validateUuid)
    fun bool(key: String): Boolean = body[key].also { require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) }.asBoolean
    fun keys(required: Set<String>) {
        val expected = required + if (operation.startsWith("create")) emptySet() else if (operation in PendingLedgerMutation.OCCURRENCE_OPERATIONS) setOf("expected_template_version", "expected_occurrence_version") else setOf("expected_version")
        require(body.keySet().all { it in expected || (operation.startsWith("create") && it == "expected_version") })
        require(expected.all(body::has))
        if (operation.startsWith("create") && body.has("expected_version")) require(body["expected_version"].asLong == 0L)
    }
    when (operation) {
        "createbudget", "editbudget" -> {
            keys(setOf("name", "category_id", "currency", "limit_minor", "period_start_day", "archived"))
            planningName(text("name")); validateUuid(text("category_id")); SupportedCurrency.fromCode(text("currency"))
            parseServerMinor(text("limit_minor"), positive = true, bounded = true); require(body["period_start_day"].asInt in 1..31); bool("archived")
        }
        "createschedule", "editschedule" -> {
            keys(setOf("name", "account_id", "category_id", "kind", "amount_minor", "note", "start_date", "end_date", "frequency", "interval", "mandatory", "primary_income", "paused", "archived"))
            planningName(text("name")); validateUuid(text("account_id")); nullableId("category_id")
            val kind = text("kind"); require(kind in setOf("expense", "income"))
            parseServerMinor(text("amount_minor"), positive = true, bounded = true); require(text("note").codePointCount(0, text("note").length) <= 500)
            val start = PlanningCalendar.validateDate(text("start_date"))
            body["end_date"].takeUnless { it.isJsonNull }?.asString?.let { require(PlanningCalendar.validateDate(it) >= start) }
            require(text("frequency") in PLANNING_FREQUENCIES && body["interval"].asInt in 1..12)
            require(!bool("mandatory") || kind == "expense"); require(!bool("primary_income") || kind == "income"); bool("paused"); bool("archived")
        }
        "editplanningsettings" -> {
            keys(setOf("reserve_minor", "account_ids", "base_currency"))
            require(parseServerMinor(text("reserve_minor"), bounded = true) >= 0); SupportedCurrency.fromCode(text("base_currency"))
            val ids = body["account_ids"].asJsonArray.map { it.asString.also(::validateUuid) }
            require(ids.size <= 200 && ids.distinct().size == ids.size)
        }
        "skipoccurrence", "unskipoccurrence" -> keys(emptySet())
        "postoccurrence" -> {
            keys(setOf("amount_minor", "occurred_at", "note", "category_id"))
            parseServerMinor(text("amount_minor"), positive = true, bounded = true); Instant.parse(text("occurred_at"))
            require(text("note").codePointCount(0, text("note").length) <= 500); nullableId("category_id")
        }
        else -> throw IllegalArgumentException("Неизвестный тип подтверждения планирования")
    }
}

internal fun validatePlanningAcknowledgement(request: PendingLedgerMutation, response: JsonObject) {
    val gson = Gson(); val body = request.json()
    fun nullable(key: String) = body[key].takeUnless { it.isJsonNull }?.asString
    fun version(actual: Long) { require(actual == if (request.operation.startsWith("create")) 1L else Math.addExact(body["expected_version"].asLong, 1)) }
    when (request.operation) {
        "createbudget", "editbudget" -> gson.fromJson(response, BudgetDto::class.java).validated().let {
            require(request.entityId == null || request.entityId == it.id); version(it.version)
            require(it.name == body["name"].asString && it.categoryId == body["category_id"].asString && it.currency == body["currency"].asString &&
                it.limitMinor == body["limit_minor"].asString && it.periodStartDay == body["period_start_day"].asInt && it.archived == body["archived"].asBoolean)
        }
        "createschedule", "editschedule" -> gson.fromJson(response, ScheduleDto::class.java).validated().let {
            require(request.entityId == null || request.entityId == it.id); version(it.version)
            require(it.name == body["name"].asString && it.accountId == body["account_id"].asString && it.categoryId == nullable("category_id") &&
                it.kind == body["kind"].asString && it.amountMinor == body["amount_minor"].asString && it.note == body["note"].asString &&
                it.startDate == body["start_date"].asString && it.endDate == nullable("end_date") && it.frequency == body["frequency"].asString &&
                it.interval == body["interval"].asInt && it.mandatory == body["mandatory"].asBoolean && it.primaryIncome == body["primary_income"].asBoolean &&
                it.paused == body["paused"].asBoolean && it.archived == body["archived"].asBoolean)
        }
        "editplanningsettings" -> gson.fromJson(response, PlanningSettingsDto::class.java).validated().let {
            version(it.version); require(it.reserveMinor == body["reserve_minor"].asString && it.baseCurrency == body["base_currency"].asString &&
                it.accountIds.toSet() == body["account_ids"].asJsonArray.map { id -> id.asString }.toSet())
        }
        "skipoccurrence", "unskipoccurrence", "postoccurrence" -> gson.fromJson(response, OccurrenceDto::class.java).validated().let {
            require(it.scheduleId == request.entityId && it.dueDate == request.occurrenceDate)
            require(it.scheduleVersion == body["expected_template_version"].asLong)
            require(it.version == Math.addExact(body["expected_occurrence_version"].asLong, 1))
            require(it.status == when (request.operation) { "skipoccurrence" -> "skipped"; "unskipoccurrence" -> "pending"; else -> "posted" })
            if (request.operation == "postoccurrence") require(it.amountMinor == body["amount_minor"].asString &&
                it.note == body["note"].asString && it.categoryId == nullable("category_id"))
        }
        else -> throw IllegalArgumentException("Неизвестный ответ планирования")
    }
}
