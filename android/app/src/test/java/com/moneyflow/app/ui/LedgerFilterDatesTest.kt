package com.moneyflow.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

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

    @Test fun exclusiveEndCaptionUsesTheLastIncludedInstantInKazakhstan() {
        assertEquals("2026-10-17", ledgerFilterInclusiveEndDate("2026-10-17T10:00:00.123457Z", true))
        assertEquals("2026-10-17", ledgerFilterInclusiveEndDate("2026-10-16T19:00:00.000001Z", true))
        assertEquals("2026-10-16", ledgerFilterInclusiveEndDate("2026-10-16T19:00:00Z", true))
        assertEquals("2026-10-31", ledgerFilterInclusiveEndDate("2026-10-31T19:00:00Z", true))
    }

    @Test fun applyingUnchangedDateFieldsKeepsPreciseStartAndAsOfCutoff() {
        val from = "2026-10-01T12:34:56.123456+05:00"
        val to = "2026-10-17T21:11:00.123457+05:00"
        assertEquals(from, ledgerFilterEditedBoundary("2026-10-01", true, false, from, true))
        assertEquals(to, ledgerFilterEditedBoundary(" 2026-10-17 ", true, true, to, true))
        val monthEnd = "2026-11-01T00:00:00+05:00"
        assertEquals(monthEnd, ledgerFilterEditedBoundary("2026-10-31", true, true, monthEnd, true))
    }

    @Test fun editedOrClearedDatesRebuildOnlyTheChangedBoundary() {
        val to = "2026-10-17T16:11:00.123457Z"
        assertEquals("2026-10-18T19:00:00Z", ledgerFilterEditedBoundary("2026-10-18", true, true, to, true))
        assertEquals("2026-10-01T19:00:00Z", ledgerFilterEditedBoundary("2026-10-02", true, false, "2026-10-01T10:00:00Z", true))
        assertNull(ledgerFilterEditedBoundary("  ", true, true, to, true))
        assertEquals("2026-10-17T19:00:00Z", ledgerFilterEditedBoundary("2026-10-17", true, true, null, true))
    }

    @Test fun changingReportZoneRebuildsDateBoundsAndLocalCaptionsRespectTheDeviceZone() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val cutoff = "2026-10-17T10:00:00.123457Z"
            assertEquals("2026-10-18T04:00:00Z", ledgerFilterEditedBoundary("2026-10-17", false, true, cutoff, true))
            assertEquals("2026-10-17T19:00:00Z", ledgerFilterEditedBoundary("2026-10-17", true, true, cutoff, false))
            assertEquals("2026-10-17", ledgerFilterInclusiveEndDate("2026-10-18T04:00:00Z", false))
            assertEquals("2026-10-18", ledgerFilterInclusiveEndDate("2026-10-18T04:00:00.000001Z", false))
            assertEquals(Instant.parse(cutoff), Instant.parse(ledgerFilterEditedBoundary("2026-10-17", false, true, cutoff, false)))
        } finally { TimeZone.setDefault(originalZone) }
    }
}
