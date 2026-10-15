package com.moneyflow.app.data.imports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** New Kazakhstan PDF rows are date-only: assign midnight at fixed UTC+05:00 once.
 * Existing server instants and durable confirmations never pass through this conversion.
 */
object KazakhstanStatementTime {
    // IANA Etc/GMT uses POSIX's reversed sign: GMT-5 is UTC+05:00.
    const val TIMEZONE = "Etc/GMT-5"
    private val offset: ZoneOffset = ZoneOffset.ofHours(5)

    fun startOfDay(date: LocalDate): Instant = date.atStartOfDay().toInstant(offset)
}
