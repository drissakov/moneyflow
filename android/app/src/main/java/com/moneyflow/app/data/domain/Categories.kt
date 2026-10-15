package com.moneyflow.app.data.domain

import java.math.BigDecimal
import java.math.RoundingMode

data class Category(
    val id: String,
    val kind: String,
    val name: String,
    val parentId: String?,
    val isDefault: Boolean,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val version: Long = 1,
)

data class AnalyticsCategory(
    val categoryId: String?,
    val name: String,
    val amountMinor: Long,
    val children: List<AnalyticsCategory> = emptyList(),
) {
    /** Round only the displayed percentage. Monetary totals always stay in integer minor units. */
    fun percentage(totalMinor: Long): BigDecimal {
        require(totalMinor >= 0 && amountMinor >= 0 && amountMinor <= totalMinor)
        return if (totalMinor == 0L) BigDecimal.ZERO else BigDecimal.valueOf(amountMinor)
            .multiply(BigDecimal(100)).divide(BigDecimal.valueOf(totalMinor), 1, RoundingMode.HALF_UP)
    }
}

data class MonthlyAnalytics(
    val month: String,
    val currency: String,
    val timezone: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val income: List<AnalyticsCategory>,
    val expenses: List<AnalyticsCategory>,
)
