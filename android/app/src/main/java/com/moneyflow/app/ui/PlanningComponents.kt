package com.moneyflow.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.math.BigDecimal
import java.math.RoundingMode

private val planningLocale = Locale.forLanguageTag("ru-KZ")

internal fun planningDate(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMMM yyyy", planningLocale))
}.getOrDefault(value)

internal fun planningShortDate(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMM", planningLocale))
}.getOrDefault(value)

internal fun planningRecurrence(value: String): String = when (value) {
    "once" -> "Один раз"
    "weekly" -> "Каждую неделю"
    "monthly" -> "Каждый месяц"
    "yearly" -> "Каждый год"
    else -> value
}

/** An incomplete conversion deliberately renders a dash, never a plausible partial total. */
@Composable
internal fun PlanningHero(available: String?, daily: String?, horizon: String, days: Int?, estimate: Boolean, deficit: Boolean = false,
    onSettings: () -> Unit) {
    Surface(shape = P0Shape, color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.fillMaxWidth().testTag("planning_safe_card")) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(if (deficit) "ДЕФИЦИТ ПО ПЛАНУ" else "МОЖНО ПОТРАТИТЬ", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text("↗", style = MaterialTheme.typography.headlineSmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(available ?: "—", style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.testTag("planning_safe_amount"))
                Text("до $horizon", style = MaterialTheme.typography.bodyMedium)
            }
            val ink = LocalContentColor.current.copy(alpha = .42f)
            Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                val marks = 29
                for (i in 0 until marks) {
                    val x = size.width * i / (marks - 1)
                    drawLine(ink, Offset(x, if (i % 7 == 0) 0f else size.height * .55f), Offset(x, size.height), 1.dp.toPx())
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(daily?.let { "$it / день" } ?: "Расчёт пока недоступен", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("planning_safe_daily"))
                Text(if (days == null) "Настройте период расчёта" else "На $days ${planningDaysWord(days)}. Резерв и обязательные платежи уже отложены.",
                    style = MaterialTheme.typography.bodySmall)
                if (estimate) Text("Оценка по доступным валютным курсам", style = MaterialTheme.typography.bodySmall)
                if (deficit) Text("На резерв и обязательные платежи пока не хватает денег. Пересмотрите план или пополните выбранные счета.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onSettings, shape = P0Shape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalContentColor.current),
                border = BorderStroke(1.dp, LocalContentColor.current.copy(alpha = .6f)),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                modifier = Modifier.fillMaxWidth().testTag("planning_settings")) { Text("Как это считается ↗") }
        }
    }
}

private fun planningDaysWord(days: Int): String = when {
    days % 100 in 11..14 -> "дней"
    days % 10 == 1 -> "день"
    days % 10 in 2..4 -> "дня"
    else -> "дней"
}

@Composable
internal fun PlanningReceiptLine(label: String, value: String, emphasized: Boolean = false, tag: String? = null) {
    Row(Modifier.fillMaxWidth().then(if (tag == null) Modifier else Modifier.testTag(tag)),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f), style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
internal fun PlanningDateChoice(label: String, value: String, tag: String, enabled: Boolean,
    onDate: (String) -> Unit) {
    val context = LocalContext.current
    P0Choice(label, planningDate(value), tag, enabled) {
        val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        DatePickerDialog(context, { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day).toString()) },
            date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
}

@Composable
internal fun PlanningEmptyState(index: String, title: String, description: String, action: String,
    tag: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = P0Shape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(index, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            P0Submit(action, tag, false, enabled, onClick)
        }
    }
}

@Composable
internal fun PlanningBudgetMeter(spent: Long, limit: Long, remaining: Long, currency: String) {
    val ratio = if (limit > 0) BigDecimal.valueOf(spent).divide(BigDecimal.valueOf(limit), 4, RoundingMode.HALF_UP).toFloat() else 0f
    val percent = if (limit > 0) BigDecimal.valueOf(spent).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(limit), 0, RoundingMode.HALF_UP).toPlainString() else "0"
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p0Money(spent, currency), style = MaterialTheme.typography.headlineSmall)
                Text("из ${p0Money(limit, currency)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("$percent%", style = MaterialTheme.typography.titleLarge)
        }
        LinearProgressIndicator(progress = { ratio.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp),
            color = MaterialTheme.colorScheme.onSurface, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        Text(if (remaining >= 0) "Осталось ${p0Money(remaining, currency)}" else "Сверх плана ${p0Money(-remaining, currency)}",
            style = MaterialTheme.typography.bodyMedium, fontWeight = if (remaining < 0) FontWeight.SemiBold else FontWeight.Normal)
    }
}
