package com.moneyflow.app.data.remote

import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.SupportedCurrency
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class FxModelsTest {
    private val usdId = "8a388b12-3811-4c9b-8911-a27b7126d06b"
    private val kztId = "8a388b12-3811-4c9b-8911-a27b7126d06c"
    private val now = Instant.parse("2026-10-11T12:00:00Z")
    private fun portfolio() = PortfolioDto("KZT", "Frankfurter", now.toString(), false, true,
        "50125", "50125", emptyList(), listOf(
            PortfolioAccountDto(usdId, "Card", "USD", "100", "50000", "500", "2026-10-09"),
            PortfolioAccountDto(kztId, "Cash", "KZT", "125", "125", "1", null),
        ))
    private fun accounts() = listOf(
        Account(usdId, "Card", "USD", 0, 100, now.toString()),
        Account(kztId, "Cash", "KZT", 0, 125, now.toString()),
    )

    @Test fun `all eleven curated currencies use correct exact units and unambiguous display`() {
        assertEquals(11, SupportedCurrency.entries.size)
        SupportedCurrency.entries.filter { it != SupportedCurrency.JPY && it != SupportedCurrency.KWD }.forEach {
            assertEquals(101L, Money.parseMinor("1.01", it))
        }
        assertEquals(1L, Money.parseMinor("1", SupportedCurrency.JPY))
        assertEquals(1001L, Money.parseMinor("1.001", SupportedCurrency.KWD))
        listOf("CAD", "AUD", "CNY").forEach { assertTrue(Money.format(100, it).endsWith(it)) }
    }

    @Test fun `positive decimal rates preserve tiny cross rates without floating point`() {
        val tiny = "0." + "0".repeat(449) + "1"
        assertEquals(BigDecimal(tiny), validateRate(tiny))
        listOf("0", "-1", "NaN", "1e-3", "+1", "1.", "01.2", "0." + "0".repeat(1024) + "1").forEach {
            assertThrows(IllegalArgumentException::class.java) { validateRate(it) }
        }
    }

    @Test fun `conversion validates signed minor amounts and original request without recomputing rounded rates`() {
        val request = ConvertRequest("KWD", "JPY", "-1001")
        val result = ConversionDto("KWD", "JPY", "-1001", "-489", "488.511488511488511488511489",
            "2026-10-09", now.toString(), false, "Frankfurter")
        assertEquals("-489", result.validated(request).convertedAmountMinor)
        assertThrows(IllegalArgumentException::class.java) { result.copy(amountMinor = "-1002").validated(request) }
        assertThrows(RuntimeException::class.java) { result.copy(rateDate = "2026-02-30").validated(request) }
        assertThrows(IllegalArgumentException::class.java) { result.copy(stale = true, fetchedAt = null).validated(request) }
    }

    @Test fun `identity conversion needs no external quote metadata`() {
        ConversionDto("USD", "USD", "100", "100", "1", null, null, false, "Frankfurter").validated()
        assertThrows(IllegalArgumentException::class.java) {
            ConversionDto("USD", "USD", "100", "101", "1", null, null, false, "Frankfurter").validated()
        }
    }

    @Test fun `portfolio full total must equal sum of individually rounded account amounts`() {
        portfolio().validated()
        assertThrows(IllegalArgumentException::class.java) { portfolio().copy(totalBalanceMinor = "50126").validated() }
        assertThrows(IllegalArgumentException::class.java) { portfolio().copy(partialBalanceMinor = "50124").validated() }
        assertThrows(RuntimeException::class.java) { portfolio().copy(fetchedAt = null).validated() }
    }

    @Test fun `partial portfolio cannot masquerade as full total`() {
        val missing = PortfolioAccountDto("8a388b12-3811-4c9b-8911-a27b7126d06d", "Dinar", "KWD", "1000", null, null, null)
        val partial = portfolio().copy(complete = false, totalBalanceMinor = null, missingCurrencies = listOf("KWD"),
            accounts = portfolio().accounts + missing)
        partial.validated()
        assertThrows(IllegalArgumentException::class.java) { partial.copy(totalBalanceMinor = "50125").validated() }
        assertThrows(IllegalArgumentException::class.java) { partial.copy(missingCurrencies = emptyList()).validated() }
    }

    @Test fun `snapshot requires identical account membership currencies and source balances`() {
        val snapshot = portfolio().validated()
        assertTrue(snapshot.matchesAccounts(accounts().reversed()))
        assertTrue(snapshot.matchesAccounts(accounts().map { it.copy(name = "Renamed") }))
        assertFalse(snapshot.matchesAccounts(accounts().drop(1)))
        assertFalse(snapshot.matchesAccounts(accounts().map { if (it.id == usdId) it.copy(balanceMinor = 101) else it }))
        assertFalse(snapshot.matchesAccounts(accounts().map { if (it.id == usdId) it.copy(currency = "CAD") else it }))
        assertFalse(snapshot.matchesAccounts(listOf(accounts().first(), accounts().first())))
    }

    @Test fun `cached quote expires based on both retrieval age and provider observation day`() {
        assertFalse(portfolio().ratesExpired(now))
        assertTrue(portfolio().copy(fetchedAt = "2026-10-04T11:59:59Z").ratesExpired(now))
        assertTrue(portfolio().copy(accounts = portfolio().accounts.map {
            if (it.currency == "USD") it.copy(rateDate = "2026-10-03") else it
        }).ratesExpired(now))
        assertTrue(portfolio().copy(fetchedAt = "2026-10-11T05:59:59Z").ratesStale(now))
        assertFalse(portfolio().ratesStale(now)) // A Friday observation remains usable on Sunday.
    }
}
