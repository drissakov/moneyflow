package com.moneyflow.app.data.remote

import com.google.gson.annotations.SerializedName
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.SupportedCurrency
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class CurrencyDto(
    val code: String, val name: String, val symbol: String,
    @SerializedName("fraction_digits") val fractionDigits: Int,
)

data class CurrencyCatalogueDto(val provider: String, val currencies: List<CurrencyDto>) {
    fun validated(): CurrencyCatalogueDto = apply {
        validateProvider(provider)
        require(currencies.map { it.code }.toSet() == SupportedCurrency.entries.map { it.code }.toSet())
        require(currencies.size == SupportedCurrency.entries.size)
        currencies.forEach {
            require(it.fractionDigits == SupportedCurrency.fromCode(it.code).scale)
            require(it.name.isNotBlank() && it.symbol.isNotBlank())
        }
    }
}

data class PreferencesDto(@SerializedName("base_currency") val baseCurrency: String) {
    fun validated(): PreferencesDto = apply { SupportedCurrency.fromCode(baseCurrency) }
}

data class ExchangeRateDto(
    @SerializedName("quote_currency") val quoteCurrency: String,
    val rate: String,
    @SerializedName("rate_date") val rateDate: String?,
)

data class ExchangeRatesDto(
    @SerializedName("base_currency") val baseCurrency: String,
    val provider: String,
    @SerializedName("fetched_at") val fetchedAt: String?,
    val stale: Boolean,
    val rates: List<ExchangeRateDto>,
    @SerializedName("missing_currencies") val missingCurrencies: List<String>,
) {
    fun validated(): ExchangeRatesDto = apply {
        SupportedCurrency.fromCode(baseCurrency)
        validateProvider(provider)
        validateFetchedAt(fetchedAt, stale)
        validateUniqueCodes(missingCurrencies)
        require(rates.map { it.quoteCurrency }.distinct().size == rates.size)
        rates.forEach {
            SupportedCurrency.fromCode(it.quoteCurrency)
            validateRate(it.rate)
            if (it.quoteCurrency == baseCurrency) {
                require(BigDecimal(it.rate).compareTo(BigDecimal.ONE) == 0)
            } else {
                requireNotNull(it.rateDate)
                requireNotNull(fetchedAt)
            }
            validateRateDate(it.rateDate)
            require(it.quoteCurrency !in missingCurrencies)
        }
    }
}

data class ConvertRequest(
    @SerializedName("from_currency") val fromCurrency: String,
    @SerializedName("to_currency") val toCurrency: String,
    @SerializedName("amount_minor") val amountMinor: String,
)

data class ConversionDto(
    @SerializedName("from_currency") val fromCurrency: String,
    @SerializedName("to_currency") val toCurrency: String,
    @SerializedName("amount_minor") val amountMinor: String,
    @SerializedName("converted_amount_minor") val convertedAmountMinor: String,
    val rate: String,
    @SerializedName("rate_date") val rateDate: String?,
    @SerializedName("fetched_at") val fetchedAt: String?,
    val stale: Boolean,
    val provider: String,
) {
    fun validated(request: ConvertRequest? = null): ConversionDto = apply {
        SupportedCurrency.fromCode(fromCurrency)
        SupportedCurrency.fromCode(toCurrency)
        parseServerMinor(amountMinor, bounded = true)
        parseServerMinor(convertedAmountMinor)
        validateRate(rate)
        validateProvider(provider)
        validateRateDate(rateDate)
        validateFetchedAt(fetchedAt, stale)
        if (fromCurrency == toCurrency) {
            require(amountMinor == convertedAmountMinor && BigDecimal(rate).compareTo(BigDecimal.ONE) == 0)
            require(rateDate == null && fetchedAt == null && !stale)
        } else {
            requireNotNull(rateDate)
            requireNotNull(fetchedAt)
        }
        request?.let {
            require(fromCurrency == it.fromCurrency && toCurrency == it.toCurrency && amountMinor == it.amountMinor)
        }
    }
}

data class PortfolioAccountDto(
    val id: String, val name: String, val currency: String,
    @SerializedName("balance_minor") val balanceMinor: String,
    @SerializedName("converted_balance_minor") val convertedBalanceMinor: String?,
    val rate: String?,
    @SerializedName("rate_date") val rateDate: String?,
)

data class PortfolioDto(
    @SerializedName("base_currency") val baseCurrency: String,
    val provider: String,
    @SerializedName("fetched_at") val fetchedAt: String?,
    val stale: Boolean,
    val complete: Boolean,
    @SerializedName("total_balance_minor") val totalBalanceMinor: String?,
    @SerializedName("partial_balance_minor") val partialBalanceMinor: String,
    @SerializedName("missing_currencies") val missingCurrencies: List<String>,
    val accounts: List<PortfolioAccountDto>,
) {
    fun validated(): PortfolioDto = apply {
        SupportedCurrency.fromCode(baseCurrency)
        validateProvider(provider)
        validateFetchedAt(fetchedAt, stale)
        validateUniqueCodes(missingCurrencies)
        require(accounts.map { it.id }.distinct().size == accounts.size)
        var partial = BigInteger.ZERO
        val unavailable = mutableSetOf<String>()
        var usesForeignRate = false
        accounts.forEach {
            validateUuid(it.id)
            require(it.name.isNotBlank())
            SupportedCurrency.fromCode(it.currency)
            parseServerMinor(it.balanceMinor)
            if (it.convertedBalanceMinor == null) {
                require(it.currency != baseCurrency && it.rate == null && it.rateDate == null)
                unavailable += it.currency
            } else {
                parseServerMinor(it.convertedBalanceMinor)
                validateRate(requireNotNull(it.rate))
                validateRateDate(it.rateDate)
                if (it.currency == baseCurrency) {
                    require(it.convertedBalanceMinor == it.balanceMinor)
                    require(BigDecimal(it.rate).compareTo(BigDecimal.ONE) == 0 && it.rateDate == null)
                } else {
                    usesForeignRate = true
                    requireNotNull(it.rateDate)
                }
                partial += BigInteger(it.convertedBalanceMinor)
            }
        }
        require(unavailable == missingCurrencies.toSet())
        require(complete == unavailable.isEmpty())
        parseServerMinor(partialBalanceMinor)
        require(parseCanonicalInteger(partialBalanceMinor) == partial)
        if (complete) {
            parseServerMinor(requireNotNull(totalBalanceMinor))
            require(parseCanonicalInteger(totalBalanceMinor) == partial)
        }
        else require(totalBalanceMinor == null)
        if (usesForeignRate) requireNotNull(fetchedAt)
    }

    /** Never display a cached valuation if any source balance, currency or account membership changed. */
    fun matchesAccounts(allAccounts: List<Account>): Boolean {
        val current = allAccounts.filter { it.includeInBalance }
        if (accounts.size != current.size || accounts.map { it.id }.distinct().size != accounts.size ||
            current.map { it.id }.distinct().size != current.size) return false
        val source = accounts.associateBy { it.id }
        return current.all { account ->
            source[account.id]?.let { it.currency == account.currency && it.balanceMinor == account.balanceMinor.toString() } == true
        }
    }

    fun ratesExpired(now: Instant = Instant.now()): Boolean = fetchedAt?.let {
        Instant.parse(it).isBefore(now.minusSeconds(7 * 24 * 60 * 60L))
    } == true || accounts.any {
        it.rateDate?.let { date -> LocalDate.parse(date).isBefore(now.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(7)) } == true
    }

    fun ratesStale(now: Instant = Instant.now()): Boolean = stale || fetchedAt?.let {
        Instant.parse(it).isBefore(now.minusSeconds(6 * 60 * 60L))
    } == true
}

internal fun validateRate(value: String): BigDecimal {
    require(value.length <= 2048 && value.matches(Regex("(?:0|[1-9]\\d*)(?:\\.\\d{1,1024})?"))) { "Некорректный курс в ответе сервера" }
    return BigDecimal(value).also { require(it.signum() > 0) }
}

internal fun parseCanonicalInteger(value: String): BigInteger {
    require(value.length <= 100 && value.matches(Regex("0|-?[1-9]\\d*"))) { "Некорректная сумма в ответе сервера" }
    return BigInteger(value)
}

private fun validateUniqueCodes(codes: List<String>) {
    require(codes.distinct().size == codes.size)
    codes.forEach { SupportedCurrency.fromCode(it) }
}

private fun validateProvider(provider: String) { require(provider == "Frankfurter") }
private fun validateRateDate(value: String?) { value?.let { require(LocalDate.parse(it).toString() == it) } }
private fun validateFetchedAt(value: String?, stale: Boolean) {
    value?.let { Instant.parse(it) }
    if (stale) requireNotNull(value)
}
