package com.moneyflow.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class PlanningTimeTest {
    @Test fun confirmationUsesKazakhstanDateAcrossMidnight() {
        assertEquals("2026-10-16T19:15:00Z", planningInstant("2026-10-17", "00:15"))
        assertEquals("2026-10-17T07:00:00Z", planningInstant("2026-10-17", "12:00"))
    }

    @Test fun confirmationDoesNotDependOnDeviceZoneOrSeason() {
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("America/New_York", "Asia/Tokyo", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals("2026-01-17T07:00:00Z", planningInstant("2026-01-17", "12:00"))
                assertEquals("2026-07-17T07:00:00Z", planningInstant("2026-07-17", "12:00"))
            }
        } finally { TimeZone.setDefault(previous) }
    }
}
