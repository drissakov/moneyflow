package com.moneyflow.app.data.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.util.UUID

data class AdvancedAnalyticsFilter(
    val month: String = PlanningCalendar.month(),
    val currency: String = "KZT",
    val months: Int = 6,
    val accountId: String? = null,
    val mode: String = "original",
    val valuation: String = "historical",
) {
    fun validate() {
        require(month.matches(Regex("\\d{4}-\\d{2}"))) { "Выберите корректный месяц" }
        require(YearMonth.parse(month).year in 1901..2199) { "Год должен быть между 1901 и 2199" }
        SupportedCurrency.fromCode(currency)
        require(months in setOf(3, 6, 12)) { "Выберите период: 3, 6 или 12 месяцев" }
        require(mode in setOf("original", "consolidated")) { "Выберите корректный режим отчёта" }
        require(valuation in setOf("historical", "latest_snapshot")) { "Выберите способ пересчёта валют" }
        require(mode != "consolidated" || (currency == "KZT" && accountId == null)) {
            "Общий отчёт доступен в тенге для всех счетов"
        }
        accountId?.let { require(UUID.fromString(it).toString().equals(it, ignoreCase = true)) { "Выберите корректный счёт" } }
    }
}

data class AdvancedAnalyticsCategory(
    val categoryId: String?, val name: String,
    val amountMinor: Long, val directAmountMinor: Long,
    val previousAmountMinor: Long, val deltaMinor: Long,
    val children: List<AdvancedAnalyticsCategory> = emptyList(),
) {
    fun percentage(total: Long): BigDecimal {
        require(total >= 0 && amountMinor in 0..total)
        return if (total == 0L) BigDecimal.ZERO else BigDecimal.valueOf(amountMinor)
            .multiply(BigDecimal(100)).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
    }
}

data class AdvancedAnalyticsSummary(
    val incomeMinor: Long, val expenseMinor: Long, val netMinor: Long,
    val previousIncomeMinor: Long, val previousExpenseMinor: Long, val previousNetMinor: Long,
    val incomeDeltaMinor: Long, val expenseDeltaMinor: Long, val netDeltaMinor: Long,
)

data class AnalyticsMonthPoint(
    val month: String, val from: String, val to: String,
    val incomeMinor: Long, val expenseMinor: Long, val netMinor: Long,
    val openingBalanceMinor: Long, val closingBalanceMinor: Long, val ledgerChangeMinor: Long,
    val openingValuationDate: String? = null, val valuationDate: String? = null,
    val convertedLedgerChangeMinor: Long? = null, val fxRevaluationMinor: Long? = null,
)

/** Audit metadata only: report money is calculated by the server. */
data class AdvancedAnalyticsRate(val currency: String, val rate: BigDecimal, val rateDate: String?,
    val valuationDate: String? = null, val sourceRateDate: String? = null, val targetRateDate: String? = null)

data class AdvancedAnalyticsConversion(
    val policy: String, val provider: String, val fetchedAt: String?, val stale: Boolean,
    val rates: List<AdvancedAnalyticsRate>,
    val fromDate: String? = null, val toDate: String? = null, val maxAgeDays: Int? = null,
)

data class AdvancedAnalyticsReport(
    val month: String, val currency: String, val timezone: String, val months: Int,
    val accountId: String?, val accountIds: List<String>,
    val from: String, val to: String, val asOf: String, val today: String, val periodStatus: String,
    val summary: AdvancedAnalyticsSummary, val series: List<AnalyticsMonthPoint>,
    val income: List<AdvancedAnalyticsCategory>, val expenses: List<AdvancedAnalyticsCategory>,
    val mode: String = "original",
    val conversion: AdvancedAnalyticsConversion? = null,
)
