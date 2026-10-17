package com.moneyflow.app.data.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** Planning uses Kazakhstan's fixed UTC+5 calendar, independent of the device's zone. */
object PlanningCalendar {
    val offset: ZoneOffset = ZoneOffset.ofHours(5)
    fun today(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.now(clock.withZone(offset))
    fun month(clock: Clock = Clock.systemUTC()): String = YearMonth.from(today(clock)).toString()
    fun validateMonth(value: String): YearMonth {
        require(value.matches(Regex("\\d{4}-\\d{2}"))) { "Выберите месяц" }
        return YearMonth.parse(value).also { require(it.year in 1900..2199) { "Год должен быть между 1900 и 2199" } }
    }
    fun validateDate(value: String): LocalDate = LocalDate.parse(value).also {
        require(it.toString() == value && it.year in 1900..2199) { "Год должен быть между 1900 и 2199" }
    }
    fun noon(date: String): String = validateDate(date).atTime(12, 0).atOffset(offset).toInstant().toString()
    fun localDate(instant: String): LocalDate = Instant.parse(instant).atOffset(offset).toLocalDate()
}
