package com.moneyflow.app.data.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class PlanningCalendarTest {
    @Test fun monthAndDateUseKazakhstanBoundaryRegardlessOfDeviceZone() {
        val now = Instant.parse("2026-10-31T20:00:00Z")
        val farWest = Clock.fixed(now, ZoneOffset.ofHours(-11))
        val farEast = Clock.fixed(now, ZoneOffset.ofHours(14))
        assertEquals("2026-11", PlanningCalendar.month(farWest))
        assertEquals(PlanningCalendar.today(farWest), PlanningCalendar.today(farEast))
        assertEquals(LocalDate.of(2026, 11, 1), PlanningCalendar.today(farEast))
        assertEquals("2026-11-01T07:00:00Z", PlanningCalendar.noon("2026-11-01"))
    }

    @Test fun strictDatesRejectOverflowAndNonCanonicalInput() {
        assertThrows(Exception::class.java) { PlanningCalendar.validateDate("2026-02-30") }
        assertThrows(Exception::class.java) { PlanningCalendar.validateMonth("2026-13") }
        assertThrows(IllegalArgumentException::class.java) { PlanningCalendar.validateMonth("2026-1") }
        assertEquals(LocalDate.of(2028, 2, 29), PlanningCalendar.validateDate("2028-02-29"))
    }
}
