package com.moneyflow.app.data.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    @Test fun `decimal input remains exact in minor units`() {
        assertEquals(1299L, Money.parseMinor("12.99", SupportedCurrency.USD))
        assertEquals(125050L, Money.parseMinor(" 1250,50 ", SupportedCurrency.KZT))
        assertEquals(-1L, Money.parseMinor("-0.01", SupportedCurrency.EUR))
        assertEquals(100L, Money.parseMinor("1.000", SupportedCurrency.USD))
    }

    @Test fun `zero and three decimal currencies use their correct scales`() {
        assertEquals(125L, Money.parseMinor("125", SupportedCurrency.JPY))
        assertEquals(1L, Money.parseMinor("0.001", SupportedCurrency.KWD))
        assertEquals("125 ¥", Money.format(125, "JPY"))
        assertEquals("-0,001 KWD", Money.format(-1, "KWD"))
    }

    @Test fun `fractional excess never rounds`() {
        assertThrows(IllegalArgumentException::class.java) { Money.parseMinor("12.999", SupportedCurrency.USD) }
        assertThrows(IllegalArgumentException::class.java) { Money.parseMinor("1.5", SupportedCurrency.JPY) }
        assertThrows(IllegalArgumentException::class.java) { Money.parseMinor("0.0001", SupportedCurrency.KWD) }
    }

    @Test fun `server write limit is enforced in both directions without limiting balance display`() {
        assertEquals(Money.MAX_INPUT_MINOR, Money.parseMinor("10000000000000.00", SupportedCurrency.USD))
        assertEquals(-Money.MAX_INPUT_MINOR, Money.parseMinor("-10000000000000.00", SupportedCurrency.USD))
        assertThrows(IllegalArgumentException::class.java) { Money.parseMinor("10000000000000.01", SupportedCurrency.USD) }
        assertThrows(IllegalArgumentException::class.java) { Money.parseMinor("-10000000000000.01", SupportedCurrency.USD) }
        assertThrows(IllegalArgumentException::class.java) { Money.parseMinor("92233720368547758.08", SupportedCurrency.USD) }
        val displayed = Money.format(Long.MAX_VALUE, "USD").substringBeforeLast(' ')
            .replace("\u00a0", "").replace("\u202f", "").replace(" ", "")
        assertEquals("92233720368547758,07", displayed)
    }

    @Test fun `transaction amount must be strictly positive`() {
        assertEquals(1L, Money.parseMinor("0.01", SupportedCurrency.KZT, positive = true))
        assertThrows(IllegalArgumentException::class.java) { Money.parseMinor("0", SupportedCurrency.KZT, positive = true) }
        assertThrows(IllegalArgumentException::class.java) { Money.parseMinor("-2", SupportedCurrency.KZT, positive = true) }
    }

    @Test fun `ambiguous or scientific input is rejected`() {
        listOf("", "1e2", "1 000", "1,000.00", ".5", "NaN", "Infinity", "--1").forEach { input ->
            assertThrows("Expected rejection: $input", IllegalArgumentException::class.java) {
                Money.parseMinor(input, SupportedCurrency.USD)
            }
        }
    }
}
