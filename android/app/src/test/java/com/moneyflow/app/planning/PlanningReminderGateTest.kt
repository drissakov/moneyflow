package com.moneyflow.app.planning

import com.moneyflow.app.data.Session
import com.moneyflow.app.data.domain.User
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PlanningReminderGateTest {
    private val now = Instant.parse("2026-10-17T06:00:00Z")
    private val today = LocalDate.parse("2026-10-17")
    private val owner = User("66666666-6666-4666-8666-666666666661", "planning@example.com")
    private val captured = Session("original-token", "2026-10-18T06:00:00Z", owner)

    private fun allowed(current: Session? = captured, active: String? = owner.id, enabled: Boolean = true,
        last: String? = null, count: Int = 1, session: Session = captured, day: LocalDate = today) =
        canDeliverPlanningReminder(session, current, active, enabled, last, day, count, now)

    @Test fun `late background response cannot notify signed out or switched user`() {
        assertTrue(allowed())
        assertFalse(allowed(current = null))
        assertFalse(allowed(current = captured.copy(user = owner.copy(id = "66666666-6666-4666-8666-666666666662"))))
        assertFalse(allowed(current = captured.copy(token = "new-token")))
        assertFalse(allowed(active = null))
        assertFalse(allowed(active = "66666666-6666-4666-8666-666666666662"))
        assertFalse(allowed(session = captured.copy(expiresAt = now.toString())))
        assertFalse(allowed(session = captured.copy(expiresAt = "invalid")))
    }

    @Test fun `user opt out empty plan and same day duplicate suppress reminder`() {
        assertFalse(allowed(enabled = false))
        assertFalse(allowed(count = 0))
        assertFalse(allowed(count = -1))
        assertFalse(allowed(last = today.toString()))
        assertTrue(allowed(last = today.minusDays(1).toString()))
        assertTrue(allowed(last = today.toString(), day = today.plusDays(1)))
    }
}
