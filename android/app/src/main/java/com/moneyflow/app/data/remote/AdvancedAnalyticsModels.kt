package com.moneyflow.app.data.remote

import com.google.gson.annotations.SerializedName
import com.moneyflow.app.data.domain.*
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
) {
    fun toDomain(expectedMonth: YearMonth): AnalyticsMonthPoint {
        require(month == expectedMonth.toString()); validateMonthBounds(expectedMonth, from, to)
        val income = nonNegative(incomeMinor); val expense = nonNegative(expenseMinor); val net = parseServerMinor(netMinor)
        val opening = parseServerMinor(openingBalanceMinor); val closing = parseServerMinor(closingBalanceMinor); val change = parseServerMinor(ledgerChangeMinor)
        difference(net, income, expense); difference(change, closing, opening)
        return AnalyticsMonthPoint(month, from, to, income, expense, net, opening, closing, change)
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
) {
    fun toDomain(expected: AdvancedAnalyticsFilter): AdvancedAnalyticsReport {
        expected.validate()
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
        require(series.size == months)
        val points = series.mapIndexed { index, row -> row.toDomain(selected.minusMonths((months - index - 1).toLong())) }
        points.zipWithNext().forEach { (left, right) -> require(left.closingBalanceMinor == right.openingBalanceMinor) }
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
            periodStatus, totals, points, incomeBuckets, expenseBuckets)
    }
}

private fun nonNegative(value: String): Long = parseServerMinor(value).also { require(it >= 0) }
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
