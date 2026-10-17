package com.moneyflow.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerFilterDatesTest {
    @Test fun reportMonthBoundariesRemainKazakhstanDays() {
        val start = ledgerFilterBoundary("2026-10-01", true, false)
        val end = ledgerFilterBoundary("2026-10-31", true, true)
        assertEquals("2026-09-30T19:00:00Z", start)
        assertEquals("2026-10-31T19:00:00Z", end)
        assertEquals("2026-10-01", ledgerFilterDate(start, true))
        assertEquals("2026-11-01", ledgerFilterDate(end, true))
    }

    @Test fun reportRowsAtMidnightUseSameDayAndTimeAsAggregates() {
        assertEquals("2026-09-30", ledgerFilterDate("2026-09-30T18:59:59Z", true))
        assertEquals("2026-10-01", ledgerFilterDate("2026-09-30T19:00:00Z", true))
        assertEquals("00:00", ledgerFilterTime("2026-09-30T19:00:00Z", true))
    }
}
