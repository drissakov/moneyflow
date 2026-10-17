package com.moneyflow.app.data.remote

import com.google.gson.annotations.SerializedName
import com.moneyflow.app.data.domain.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

data class AdvancedAnalyticsCategoryDto(
    @SerializedName("category_id") val categoryId: String?, val name: String,
    @SerializedName("amount_minor") val amountMinor: String,
    @SerializedName("direct_amount_minor") val directAmountMinor: String,
    @SerializedName("previous_amount_minor") val previousAmountMinor: String,
    @SerializedName("delta_minor") val deltaMinor: String,
    val children: List<AdvancedAnalyticsCategoryDto>,
) {
    fun toDomain(allowChildren: Boolean): AdvancedAnalyticsCategory {
        categoryId?.let(::validateUuid); validateCategoryName(name)
        val amount = nonNegative(amountMinor); val direct = nonNegative(directAmountMinor)
        val previous = nonNegative(previousAmountMinor); val delta = parseServerMinor(deltaMinor)
        require(BigInteger.valueOf(delta) == BigInteger.valueOf(amount) - BigInteger.valueOf(previous))
        require(allowChildren || children.isEmpty())
        require(categoryId != null || children.isEmpty())
        val nested = children.map { it.toDomain(false) }
        require(nested.all { it.categoryId != null && it.categoryId != categoryId })
        require(nested.map { it.categoryId }.distinct().size == nested.size)
        require(BigInteger.valueOf(amount) == BigInteger.valueOf(direct) + nested.sumExact { it.amountMinor })
        require(nested.sumExact { it.previousAmountMinor } <= BigInteger.valueOf(previous))
        return AdvancedAnalyticsCategory(categoryId, name, amount, direct, previous, delta, nested)
    }
}

data class AdvancedAnalyticsSummaryDto(
    @SerializedName("income_minor") val incomeMinor: String,
    @SerializedName("expense_minor") val expenseMinor: String,
    @SerializedName("net_minor") val netMinor: String,
    @SerializedName("previous_income_minor") val previousIncomeMinor: String,
    @SerializedName("previous_expense_minor") val previousExpenseMinor: String,
    @SerializedName("previous_net_minor") val previousNetMinor: String,
    @SerializedName("income_delta_minor") val incomeDeltaMinor: String,
    @SerializedName("expense_delta_minor") val expenseDeltaMinor: String,
    @SerializedName("net_delta_minor") val netDeltaMinor: String,
) {
    fun toDomain(): AdvancedAnalyticsSummary {
        val income = nonNegative(incomeMinor); val expense = nonNegative(expenseMinor); val net = parseServerMinor(netMinor)
        val previousIncome = nonNegative(previousIncomeMinor); val previousExpense = nonNegative(previousExpenseMinor)
        val previousNet = parseServerMinor(previousNetMinor)
        val incomeDelta = parseServerMinor(incomeDeltaMinor); val expenseDelta = parseServerMinor(expenseDeltaMinor); val netDelta = parseServerMinor(netDeltaMinor)
        difference(net, income, expense); difference(previousNet, previousIncome, previousExpense)
        difference(incomeDelta, income, previousIncome); difference(expenseDelta, expense, previousExpense); difference(netDelta, net, previousNet)
        return AdvancedAnalyticsSummary(income, expense, net, previousIncome, previousExpense, previousNet, incomeDelta, expenseDelta, netDelta)
    }
}

data class AnalyticsMonthPointDto(
    val month: String, val from: String, val to: String,
    @SerializedName("income_minor") val incomeMinor: String,
    @SerializedName("expense_minor") val expenseMinor: String,
    @SerializedName("net_minor") val netMinor: String,
    @SerializedName("opening_balance_minor") val openingBalanceMinor: String,
    @SerializedName("closing_balance_minor") val closingBalanceMinor: String,
    @SerializedName("ledger_change_minor") val ledgerChangeMinor: String,
    @SerializedName("opening_valuation_date") val openingValuationDate: String? = null,
    @SerializedName("valuation_date") val valuationDate: String? = null,
    @SerializedName("converted_ledger_change_minor") val convertedLedgerChangeMinor: String? = null,
    @SerializedName("fx_revaluation_minor") val fxRevaluationMinor: String? = null,
) {
    fun toDomain(expectedMonth: YearMonth, historicalToday: LocalDate? = null): AnalyticsMonthPoint {
        require(month == expectedMonth.toString()); validateMonthBounds(expectedMonth, from, to)
        val income = nonNegative(incomeMinor); val expense = nonNegative(expenseMinor); val net = parseServerMinor(netMinor)
        val opening = parseServerMinor(openingBalanceMinor); val closing = parseServerMinor(closingBalanceMinor); val change = parseServerMinor(ledgerChangeMinor)
        difference(net, income, expense); difference(change, closing, opening)
        val flow: Long?; val revaluation: Long?
        if (historicalToday != null) {
            require(expectedMonth <= YearMonth.from(historicalToday))
            require(openingValuationDate == expectedMonth.atDay(1).minusDays(1).toString())
            require(valuationDate == minOf(expectedMonth.atEndOfMonth(), historicalToday).toString())
            flow = parseServerMinor(requireNotNull(convertedLedgerChangeMinor))
            revaluation = parseServerMinor(requireNotNull(fxRevaluationMinor))
            difference(revaluation, change, flow)
        } else {
            require(openingValuationDate == null && valuationDate == null && convertedLedgerChangeMinor == null && fxRevaluationMinor == null)
            flow = null; revaluation = null
        }
        return AnalyticsMonthPoint(month, from, to, income, expense, net, opening, closing, change,
            openingValuationDate, valuationDate, flow, revaluation)
    }
}

data class AdvancedAnalyticsRateDto(
    val currency: String, val rate: String,
    @SerializedName("rate_date") val rateDate: String?,
    @SerializedName("valuation_date") val valuationDate: String? = null,
    @SerializedName("source_rate_date") val sourceRateDate: String? = null,
    @SerializedName("target_rate_date") val targetRateDate: String? = null,
) {
    fun toDomain(historicalFrom: LocalDate? = null, historicalTo: LocalDate? = null): AdvancedAnalyticsRate {
        SupportedCurrency.fromCode(currency)
        val exact = validateRate(rate)
        rateDate?.let(::exactDate)
        if (historicalFrom != null && historicalTo != null) {
            require(currency != "KZT")
            val valuation = exactDate(requireNotNull(valuationDate))
            require(valuation in historicalFrom..historicalTo)
            val source = sourceRateDate?.let(::exactDate)
            val target = exactDate(requireNotNull(targetRateDate))
            require((currency == "USD") == (source == null))
            listOfNotNull(source, target).forEach { require(it <= valuation && !it.isBefore(valuation.minusDays(14))) }
            require(rateDate == listOfNotNull(source, target).minOrNull().toString())
        } else {
            require(valuationDate == null && sourceRateDate == null && targetRateDate == null)
            if (currency == "KZT") require(exact.compareTo(BigDecimal.ONE) == 0 && rateDate == null)
            else requireNotNull(rateDate)
        }
        return AdvancedAnalyticsRate(currency, exact, rateDate, valuationDate, sourceRateDate, targetRateDate)
    }
}

data class AdvancedAnalyticsConversionDto(
    val policy: String, val provider: String,
    @SerializedName("fetched_at") val fetchedAt: String?,
    val stale: Boolean, val rates: List<AdvancedAnalyticsRateDto>,
    @SerializedName("from_date") val fromDate: String? = null,
    @SerializedName("to_date") val toDate: String? = null,
    @SerializedName("max_age_days") val maxAgeDays: Int? = null,
) {
    fun toDomain(hasAccounts: Boolean, expectedPolicy: String = "latest_snapshot", expectedFrom: LocalDate? = null,
        expectedTo: LocalDate? = null): AdvancedAnalyticsConversion {
        require(policy == expectedPolicy && policy in setOf("latest_snapshot", "historical") && provider == "Frankfurter") { "Неизвестный источник курсов отчёта" }
        fetchedAt?.let(Instant::parse)
        val validated = if (policy == "historical") {
            require(fromDate == requireNotNull(expectedFrom).toString() && toDate == requireNotNull(expectedTo).toString() && maxAgeDays == 14)
            val keys = rates.map { "${it.valuationDate}:${it.currency}" }
            require(keys == keys.distinct().sorted()) { "Курсы валют должны быть уникальными и упорядоченными по датам" }
            require(hasAccounts || rates.isEmpty())
            rates.map { it.toDomain(expectedFrom, expectedTo) }
        } else {
            require(fromDate == null && toDate == null && maxAgeDays == null)
            val codes = rates.map { it.currency }
            require(codes == codes.distinct().sorted()) { "Курсы валют должны быть уникальными и упорядоченными" }
            require(hasAccounts == rates.isNotEmpty()) { "Курсы отчёта не соответствуют списку счетов" }
            rates.map { it.toDomain() }
        }
        if (validated.any { it.currency != "KZT" }) requireNotNull(fetchedAt)
        else require(fetchedAt == null && !stale)
        if (stale) requireNotNull(fetchedAt)
        return AdvancedAnalyticsConversion(policy, provider, fetchedAt, stale, validated, fromDate, toDate, maxAgeDays)
    }
}

data class AdvancedAnalyticsReportDto(
    val month: String, val currency: String, val timezone: String, val months: Int,
    @SerializedName("account_id") val accountId: String?,
    @SerializedName("account_ids") val accountIds: List<String>,
    val from: String, val to: String,
    @SerializedName("as_of") val asOf: String, val today: String,
    @SerializedName("period_status") val periodStatus: String,
    val summary: AdvancedAnalyticsSummaryDto, val series: List<AnalyticsMonthPointDto>,
    val income: List<AdvancedAnalyticsCategoryDto>, val expenses: List<AdvancedAnalyticsCategoryDto>,
    // Gson leaves an absent field null in reports cached by versions predating consolidated reports.
    val mode: String? = null,
    val conversion: AdvancedAnalyticsConversionDto? = null,
) {
    fun toDomain(expected: AdvancedAnalyticsFilter): AdvancedAnalyticsReport {
        expected.validate()
        val reportMode = mode ?: "original"
        require(reportMode == expected.mode) { "Сервер вернул отчёт для другого режима" }
        require(month == expected.month && currency == expected.currency && months == expected.months && accountId == expected.accountId) {
            "Сервер вернул отчёт для другого периода, валюты или счёта"
        }
        require(timezone == "UTC+05:00")
        accountId?.let(::validateUuid); accountIds.forEach(::validateUuid)
        require(accountIds == accountIds.distinct().sorted())
        if (accountId != null) require(accountIds == listOf(accountId))
        val selected = YearMonth.parse(month); validateMonthBounds(selected, from, to)
        val stamp = Instant.parse(asOf); val date = LocalDate.parse(today)
        require(date.toString() == today && stamp.atOffset(ZoneOffset.ofHours(5)).toLocalDate() == date)
        val currentMonth = YearMonth.from(date)
        require(periodStatus == when { selected < currentMonth -> "past"; selected > currentMonth -> "future"; else -> "current" })
        val historical = reportMode == "consolidated" && expected.valuation == "historical"
        if (historical) require(selected <= currentMonth) { "Исторический отчёт недоступен для будущего месяца" }
        val conversionSnapshot = if (reportMode == "consolidated") {
            require(currency == "KZT" && accountId == null)
            requireNotNull(conversion) { "В общем отчёте отсутствуют курсы конвертации" }.toDomain(accountIds.isNotEmpty(), expected.valuation,
                selected.minusMonths((months - 1).toLong()).atDay(1).minusDays(1).takeIf { historical },
                minOf(selected.atEndOfMonth(), date).takeIf { historical })
        } else {
            require(conversion == null) { "Отчёт в исходной валюте не должен содержать конвертацию" }; null
        }
        require(series.size == months)
        val points = series.mapIndexed { index, row -> row.toDomain(selected.minusMonths((months - index - 1).toLong()), date.takeIf { historical }) }
        points.zipWithNext().forEach { (left, right) -> require(left.closingBalanceMinor == right.openingBalanceMinor) }
        if (historical) {
            val boundaries = points.mapNotNull { it.valuationDate } + requireNotNull(points.first().openingValuationDate)
            conversionSnapshot!!.rates.groupBy { it.currency }.values.forEach { rates ->
                require(rates.mapNotNull { it.valuationDate }.containsAll(boundaries)) { "В отчёте отсутствуют курсы на границах месяцев" }
            }
        }
        val totals = summary.toDomain(); val latest = points.last(); val previous = points[points.lastIndex - 1]
        require(totals.incomeMinor == latest.incomeMinor && totals.expenseMinor == latest.expenseMinor && totals.netMinor == latest.netMinor)
        require(totals.previousIncomeMinor == previous.incomeMinor && totals.previousExpenseMinor == previous.expenseMinor && totals.previousNetMinor == previous.netMinor)
        val incomeBuckets = income.map { it.toDomain(false) }; val expenseBuckets = expenses.map { it.toDomain(true) }
        listOf(incomeBuckets, expenseBuckets).forEach { buckets ->
            val ids = buckets.flatMap { listOf(it.categoryId) + it.children.map(AdvancedAnalyticsCategory::categoryId) }
            require(ids.distinct().size == ids.size)
        }
        require(incomeBuckets.sumExact { it.amountMinor } == BigInteger.valueOf(totals.incomeMinor))
        require(expenseBuckets.sumExact { it.amountMinor } == BigInteger.valueOf(totals.expenseMinor))
        require(incomeBuckets.sumExact { it.previousAmountMinor } == BigInteger.valueOf(totals.previousIncomeMinor))
        require(expenseBuckets.sumExact { it.previousAmountMinor } == BigInteger.valueOf(totals.previousExpenseMinor))
        return AdvancedAnalyticsReport(month, currency, timezone, months, accountId, accountIds, from, to, asOf, today,
            periodStatus, totals, points, incomeBuckets, expenseBuckets, reportMode, conversionSnapshot)
    }
}

private fun nonNegative(value: String): Long = parseServerMinor(value).also { require(it >= 0) }
private fun exactDate(value: String): LocalDate = LocalDate.parse(value).also { require(it.toString() == value) }
private fun difference(actual: Long, left: Long, right: Long) {
    require(BigInteger.valueOf(actual) == BigInteger.valueOf(left) - BigInteger.valueOf(right)) { "Итоги отчёта не согласованы" }
}
private fun List<AdvancedAnalyticsCategory>.sumExact(amount: (AdvancedAnalyticsCategory) -> Long): BigInteger =
    fold(BigInteger.ZERO) { sum, row -> sum + BigInteger.valueOf(amount(row)) }
private fun validateMonthBounds(month: YearMonth, from: String, to: String) {
    val offset = ZoneOffset.ofHours(5)
    require(Instant.parse(from) == month.atDay(1).atStartOfDay().atOffset(offset).toInstant())
    require(Instant.parse(to) == month.plusMonths(1).atDay(1).atStartOfDay().atOffset(offset).toInstant())
}
