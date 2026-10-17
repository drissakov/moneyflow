package com.moneyflow.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Report drill-down must keep the same day boundaries as the server's UTC+5 aggregates. */
internal fun ledgerFilterZone(reportScope: Boolean): ZoneId = if (reportScope) ZoneOffset.ofHours(5) else ZoneId.systemDefault()

internal fun ledgerFilterDate(value: String, reportScope: Boolean): String =
    Instant.parse(value).atZone(ledgerFilterZone(reportScope)).toLocalDate().toString()

internal fun ledgerFilterInclusiveEndDate(value: String, reportScope: Boolean): String =
    Instant.parse(value).minusNanos(1).atZone(ledgerFilterZone(reportScope)).toLocalDate().toString()

internal fun ledgerFilterTime(value: String, reportScope: Boolean): String =
    Instant.parse(value).atZone(ledgerFilterZone(reportScope)).format(DateTimeFormatter.ofPattern("HH:mm"))

internal fun ledgerFilterBoundary(date: String, reportScope: Boolean, endInclusive: Boolean): String {
    val day = LocalDate.parse(date).let { if (endInclusive) it.plusDays(1) else it }
    return day.atStartOfDay(ledgerFilterZone(reportScope)).toInstant().toString()
}

/** Applying another filter must preserve an unchanged report's precise as-of cutoff. */
internal fun ledgerFilterEditedBoundary(date: String, reportScope: Boolean, endInclusive: Boolean,
    originalValue: String?, originalReportScope: Boolean): String? {
    val selected = date.trim().takeIf { it.isNotEmpty() } ?: return null
    if (originalValue != null && reportScope == originalReportScope) {
        val originalDate = if (endInclusive) ledgerFilterInclusiveEndDate(originalValue, reportScope) else ledgerFilterDate(originalValue, reportScope)
        if (selected == originalDate) return originalValue
    }
    return ledgerFilterBoundary(selected, reportScope, endInclusive)
}
