package com.moneyflow.app.data.remote

import com.google.gson.annotations.SerializedName
import com.moneyflow.app.data.domain.AnalyticsCategory
import com.moneyflow.app.data.domain.Category
import com.moneyflow.app.data.domain.MonthlyAnalytics
import com.moneyflow.app.data.domain.SupportedCurrency
import java.time.YearMonth
import java.time.ZoneId

data class CategoryDto(
    val id: String,
    val kind: String,
    val name: String,
    @SerializedName("parent_id") val parentId: String?,
    @SerializedName("is_default") val isDefault: Boolean,
    val archived: Boolean? = null,
    @SerializedName("sort_order") val sortOrder: Int? = null,
    val version: Long? = null,
) {
    fun toDomain(): Category {
        validateUuid(id)
        validateCategoryFields(kind, name, parentId)
        require(id != parentId) { "Категория не может быть собственной подкатегорией" }
        return Category(id, kind, name, parentId, isDefault, archived ?: false, sortOrder ?: 0,
            (version ?: 1).also { require(it >= 1) })
    }
}

data class CategoriesResponse(val categories: List<CategoryDto>) {
    fun toDomain(): List<Category> {
        val result = categories.map(CategoryDto::toDomain)
        require(result.map(Category::id).distinct().size == result.size) { "Повторяющиеся категории в ответе сервера" }
        val byId = result.associateBy(Category::id)
        result.forEach { category ->
            category.parentId?.let { parentId ->
                val parent = requireNotNull(byId[parentId]) { "Родительская категория не найдена" }
                require(parent.kind == "expense" && parent.parentId == null) { "Некорректная иерархия категорий" }
            }
        }
        return result
    }
}

data class CreateCategoryRequest(
    val kind: String,
    val name: String,
    @SerializedName("parent_id") val parentId: String? = null,
) {
    fun validate() = validateCategoryFields(kind, name, parentId)
}

data class SetTransactionCategoryRequest(@SerializedName("category_id") val categoryId: String?)

data class AnalyticsCategoryDto(
    @SerializedName("category_id") val categoryId: String?,
    val name: String,
    @SerializedName("amount_minor") val amountMinor: String,
    val children: List<AnalyticsCategoryDto>,
) {
    internal fun toDomain(allowChildren: Boolean): AnalyticsCategory {
        categoryId?.let(::validateUuid)
        validateCategoryName(name)
        val amount = parseServerMinor(amountMinor)
        require(amount >= 0) { "Отрицательная сумма в аналитике" }
        require(allowChildren || children.isEmpty()) { "Доходы и подкатегории должны быть плоскими" }
        val nested = children.map { it.toDomain(false) }
        require(nested.all { it.categoryId != null }) { "Подкатегория должна иметь идентификатор" }
        require(nested.map { it.categoryId }.distinct().size == nested.size) { "Повторяющиеся подкатегории" }
        val childTotal = checkedAnalyticsSum(nested)
        require(childTotal <= amount) { "Сумма подкатегорий превышает категорию" }
        if (categoryId == null) require(nested.isEmpty()) { "Операции без категории не могут иметь подкатегории" }
        return AnalyticsCategory(categoryId, name, amount, nested)
    }
}

data class MonthlyAnalyticsDto(
    val month: String,
    val currency: String,
    val timezone: String,
    @SerializedName("income_minor") val incomeMinor: String,
    @SerializedName("expense_minor") val expenseMinor: String,
    val income: List<AnalyticsCategoryDto>,
    val expenses: List<AnalyticsCategoryDto>,
) {
    fun toDomain(expectedMonth: String, expectedCurrency: String, expectedTimezone: String): MonthlyAnalytics {
        require(month.matches(Regex("\\d{4}-\\d{2}")))
        YearMonth.parse(month)
        SupportedCurrency.fromCode(currency)
        ZoneId.of(timezone)
        require(month == expectedMonth && currency == expectedCurrency && timezone == expectedTimezone) {
            "Сервер вернул аналитику для другого периода или валюты"
        }
        val totalIncome = parseServerMinor(incomeMinor)
        val totalExpenses = parseServerMinor(expenseMinor)
        require(totalIncome >= 0 && totalExpenses >= 0)
        val incomeRows = income.map { it.toDomain(false) }
        val expenseRows = expenses.map { it.toDomain(true) }
        listOf(incomeRows, expenseRows).forEach { rows ->
            val ids = rows.flatMap { listOf(it.categoryId) + it.children.map(AnalyticsCategory::categoryId) }
            require(ids.distinct().size == ids.size) { "Повторяющиеся категории в аналитике" }
        }
        require(checkedAnalyticsSum(incomeRows) == totalIncome && checkedAnalyticsSum(expenseRows) == totalExpenses) {
            "Итоги аналитики не соответствуют категориям"
        }
        return MonthlyAnalytics(month, currency, timezone, totalIncome, totalExpenses, incomeRows, expenseRows)
    }
}

private fun checkedAnalyticsSum(rows: List<AnalyticsCategory>): Long = rows.fold(0L) { sum, row ->
    try { Math.addExact(sum, row.amountMinor) } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Сумма аналитики превышает допустимый размер")
    }
}

internal fun validateCategoryName(name: String) {
    require(name == name.trim() && name.codePointCount(0, name.length) in 1..100) {
        "Название категории: от 1 до 100 символов"
    }
}

/** Match Go strings.Fields so the submitted name and the server acknowledgement use one spelling. */
internal fun normalizeCategoryName(name: String): String = buildString {
    var pendingSpace = false
    name.forEach { character ->
        val isSpace = (character.isWhitespace() && character !in '\u001c'..'\u001f') || character == '\u0085'
        if (isSpace) {
            pendingSpace = isNotEmpty()
        } else {
            if (pendingSpace) append(' ')
            append(character)
            pendingSpace = false
        }
    }
}

private fun validateCategoryFields(kind: String, name: String, parentId: String?) {
    require(kind == "expense" || kind == "income") { "Некорректный тип категории" }
    validateCategoryName(name)
    parentId?.let(::validateUuid)
    require(kind == "expense" || parentId == null) { "У категорий доходов нет подкатегорий" }
}
