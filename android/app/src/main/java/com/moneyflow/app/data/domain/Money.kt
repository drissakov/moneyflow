package com.moneyflow.app.data.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

enum class SupportedCurrency(val code: String, val scale: Int, val symbol: String, val title: String) {
    KZT("KZT", 2, "₸", "Казахстанский тенге"),
    USD("USD", 2, "$", "Доллар США"),
    EUR("EUR", 2, "€", "Евро"),
    JPY("JPY", 0, "¥", "Японская иена"),
    KWD("KWD", 3, "KWD", "Кувейтский динар"),
    RUB("RUB", 2, "₽", "Российский рубль"),
    GBP("GBP", 2, "£", "Фунт стерлингов"),
    CHF("CHF", 2, "CHF", "Швейцарский франк"),
    AUD("AUD", 2, "AUD", "Австралийский доллар"),
    CAD("CAD", 2, "CAD", "Канадский доллар"),
    CNY("CNY", 2, "CNY", "Китайский юань");

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
        return format(minor.toString(), code)
    }

    fun format(minor: String, code: String): String {
        require(minor.matches(Regex("0|-?[1-9]\\d*"))) { "Некорректная сумма" }
        val currency = SupportedCurrency.fromCode(code)
        val symbols = DecimalFormatSymbols(Locale.forLanguageTag("ru-RU"))
        val formatter = DecimalFormat().apply {
            decimalFormatSymbols = symbols
            minimumFractionDigits = currency.scale
            maximumFractionDigits = currency.scale
            isGroupingUsed = true
            groupingSize = 3
        }
        return "${formatter.format(BigDecimal(minor).movePointLeft(currency.scale))} ${currency.symbol}"
    }
}
