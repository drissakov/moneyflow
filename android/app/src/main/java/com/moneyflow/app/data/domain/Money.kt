package com.moneyflow.app.data.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

enum class SupportedCurrency(val code: String, val scale: Int, val symbol: String) {
    KZT("KZT", 2, "₸"),
    USD("USD", 2, "$"),
    EUR("EUR", 2, "€"),
    JPY("JPY", 0, "¥"),
    KWD("KWD", 3, "KWD");

    companion object {
        fun fromCode(code: String): SupportedCurrency = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Валюта $code не поддерживается")
    }
}

object Money {
    const val MAX_INPUT_MINOR = 1_000_000_000_000_000L
    /** No floating point, grouping, scientific notation, implicit rounding or overflow. */
    fun parseMinor(text: String, currency: SupportedCurrency, positive: Boolean = false): Long {
        val normalized = text.trim().replace(',', '.')
        require(normalized.matches(Regex("-?\\d+(\\.\\d+)?"))) { "Введите сумму, например 1250,50" }
        val amount = try {
            BigDecimal(normalized).setScale(currency.scale, RoundingMode.UNNECESSARY)
                .movePointRight(currency.scale).longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Допустимо ${currency.scale} знаков после запятой. Проверьте размер суммы")
        }
        require(amount in -MAX_INPUT_MINOR..MAX_INPUT_MINOR) { "Сумма превышает допустимый размер операции" }
        if (positive) require(amount > 0) { "Сумма должна быть больше нуля" }
        return amount
    }

    fun format(minor: Long, code: String): String {
        val currency = SupportedCurrency.fromCode(code)
        val symbols = DecimalFormatSymbols(Locale.forLanguageTag("ru-RU"))
        val formatter = DecimalFormat().apply {
            decimalFormatSymbols = symbols
            minimumFractionDigits = currency.scale
            maximumFractionDigits = currency.scale
            isGroupingUsed = true
            groupingSize = 3
        }
        return "${formatter.format(BigDecimal.valueOf(minor, currency.scale))} ${currency.symbol}"
    }
}
