package com.moneyflow.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.AnalyticsMonthPoint
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/** Money stays integral. A decimal ratio becomes Float only at the drawing boundary. */
internal fun analyticsPosition(value: Long, min: Long, max: Long): Float {
    if (min == max) return .5f
    val numerator = BigInteger.valueOf(value) - BigInteger.valueOf(min)
    val denominator = BigInteger.valueOf(max) - BigInteger.valueOf(min)
    return BigDecimal(numerator).divide(BigDecimal(denominator), 8, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
}

internal fun analyticsMonthName(month: String, full: Boolean = false): String = runCatching {
    YearMonth.parse(month).format(DateTimeFormatter.ofPattern(if (full) "LLLL yyyy" else "LLL", Locale.forLanguageTag("ru-KZ")))
        .replaceFirstChar { it.titlecase() }
}.getOrDefault(month)

@Composable
internal fun ComparisonPatternLegend() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        PatternLegendItem("Доходы", false, Modifier.weight(1f))
        PatternLegendItem("Расходы", true, Modifier.weight(1f))
    }
}

@Composable
private fun PatternLegendItem(label: String, hatch: Boolean, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(20.dp)) {
            if (!hatch) drawRect(ink) else {
                drawRect(ink, style = Stroke(1.5.dp.toPx()))
                clipRect { for (i in -4..8) drawLine(ink, Offset(i * 5.dp.toPx(), size.height), Offset(i * 5.dp.toPx() + size.height, 0f), 1.dp.toPx()) }
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun MonthlyComparisonBars(series: List<AnalyticsMonthPoint>, currency: String, selectedMonth: String,
    onSelect: (String) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outlineVariant
    val maximum = series.maxOfOrNull { maxOf(it.incomeMinor, it.expenseMinor) } ?: 0L
    if (series.isEmpty()) { P0Message("Для выбранного периода пока нет данных."); return }
    val scroll = rememberScrollState()
    LaunchedEffect(series.first().month, series.last().month) {
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        scroll.scrollTo(scroll.maxValue)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ComparisonPatternLegend()
        Text(if (maximum == 0L) "В этом периоде нет доходов и расходов." else "Одинаковая шкала для всех месяцев. Нажмите на месяц, чтобы увидеть точные суммы.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (maximum > 0L) Text("Верх шкалы — ${p0Money(maximum, currency)}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val chartWidth = maxOf(maxWidth, (series.size * 82).dp)
            Row(Modifier.horizontalScroll(scroll).testTag("reports_comparison_scroll")) {
                Row(Modifier.width(chartWidth)) {
                    series.forEach { point ->
                        val isSelected = selectedMonth == point.month
                        Column(Modifier.width(chartWidth / series.size).clickable(role = Role.Button) { onSelect(point.month) }
                            .semantics(mergeDescendants = true) {
                                selected = isSelected
                                contentDescription = "${analyticsMonthName(point.month, true)}. Доходы ${p0Money(point.incomeMinor, currency)}, расходы ${p0Money(point.expenseMinor, currency)}, разница ${p0Money(point.netMinor, currency)}. Выбрать месяц."
                            }.testTag("reports_bar_${point.month}"), horizontalAlignment = Alignment.CenterHorizontally) {
                            Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                                val baseline = size.height - 10.dp.toPx()
                                val height = baseline - 10.dp.toPx()
                                val barWidth = minOf(22.dp.toPx(), size.width * .26f)
                                val gap = 6.dp.toPx()
                                val left = size.width / 2 - barWidth - gap / 2
                                drawLine(outline, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
                                val incomeHeight = if (maximum == 0L) 0f else analyticsPosition(point.incomeMinor, 0, maximum) * height
                                val expenseHeight = if (maximum == 0L) 0f else analyticsPosition(point.expenseMinor, 0, maximum) * height
                                if (incomeHeight > 0) drawRect(ink, Offset(left, baseline - incomeHeight), Size(barWidth, incomeHeight))
                                else drawLine(ink, Offset(left, baseline), Offset(left + barWidth, baseline), 2.dp.toPx())
                                val right = left + barWidth + gap
                                if (expenseHeight > 0) {
                                    drawRect(ink, Offset(right, baseline - expenseHeight), Size(barWidth, expenseHeight), style = Stroke(1.5.dp.toPx()))
                                    clipRect(right, baseline - expenseHeight, right + barWidth, baseline) {
                                        val step = 7.dp.toPx()
                                        for (i in -1..((expenseHeight + barWidth) / step).roundToInt() + 1) {
                                            val y = baseline - i * step
                                            drawLine(ink, Offset(right, y), Offset(right + barWidth, y - barWidth), 1.dp.toPx())
                                        }
                                    }
                                } else drawLine(ink, Offset(right, baseline), Offset(right + barWidth, baseline), 2.dp.toPx())
                            }
                            Surface(shape = P0Shape, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp).heightIn(min = 52.dp)) {
                                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(analyticsMonthName(point.month), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                                    Text(point.month.take(4), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MonthlyBalanceLine(series: List<AnalyticsMonthPoint>, currency: String, selectedMonth: String, onSelect: (String) -> Unit) {
    if (series.isEmpty()) { P0Message("Для выбранного периода пока нет данных."); return }
    val values = listOf(series.first().openingBalanceMinor) + series.map { it.closingBalanceMinor }
    val minimum = minOf(0L, values.minOrNull() ?: 0L)
    val maximum = maxOf(0L, values.maxOrNull() ?: 0L)
    val ink = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val selectedIndex = series.indexOfFirst { it.month == selectedMonth }.coerceAtLeast(0) + 1
    val scroll = rememberScrollState()
    LaunchedEffect(series.first().month, series.last().month) {
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        scroll.scrollTo(scroll.maxValue)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Верх шкалы\n${p0Money(maximum, currency)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text("Низ шкалы\n${p0Money(minimum, currency)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val chartWidth = maxOf(maxWidth, (series.size * 74 + 24).dp)
            Column(Modifier.horizontalScroll(scroll).testTag("reports_balance_scroll")) {
                Canvas(Modifier.width(chartWidth).height(220.dp).testTag("reports_balance_chart")
                    .semantics { contentDescription = "Динамика остатка в $currency. Начало ${p0Money(values.first(), currency)}, конец ${p0Money(values.last(), currency)}. Точные остатки по месяцам приведены ниже." }
                    .pointerInput(series) {
                        detectTapGestures { tap ->
                            val inset = 16.dp.toPx()
                            val index = ((tap.x - inset) / (size.width - inset * 2) * series.size).roundToInt().coerceIn(1, series.size)
                            onSelect(series[index - 1].month)
                        }
                    }) {
                    val inset = 16.dp.toPx()
                    val top = 18.dp.toPx()
                    val bottom = size.height - 18.dp.toPx()
                    fun y(value: Long) = bottom - analyticsPosition(value, minimum, maximum) * (bottom - top)
                    fun x(index: Int) = inset + (size.width - inset * 2) * index / series.size
                    val zeroY = y(0)
                    drawLine(grid, Offset(inset, zeroY), Offset(size.width - inset, zeroY), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))
                    val path = Path().apply {
                        values.forEachIndexed { index, value -> if (index == 0) moveTo(x(index), y(value)) else lineTo(x(index), y(value)) }
                    }
                    drawPath(path, ink, style = Stroke(2.5.dp.toPx()))
                    values.forEachIndexed { index, value ->
                        val center = Offset(x(index), y(value))
                        if (index == selectedIndex) {
                            drawLine(grid, Offset(center.x, top), Offset(center.x, bottom), 1.dp.toPx())
                            drawCircle(surface, 7.dp.toPx(), center)
                            drawCircle(ink, 7.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                        } else drawCircle(ink, 3.dp.toPx(), center)
                    }
                }
                Row(Modifier.width(chartWidth).padding(start = 16.dp)) {
                    series.forEach { point ->
                        val isSelected = point.month == selectedMonth
                        Surface(shape = P0Shape, color = if (isSelected) MaterialTheme.colorScheme.primary else surface,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else ink,
                            border = BorderStroke(1.dp, if (isSelected) ink else grid),
                            modifier = Modifier.width((chartWidth - 16.dp) / series.size).heightIn(min = 52.dp)
                                .clickable(role = Role.Button) { onSelect(point.month) }.semantics(mergeDescendants = true) {
                                    selected = isSelected
                                    contentDescription = "${analyticsMonthName(point.month, true)}. На начало ${p0Money(point.openingBalanceMinor, currency)}, на конец ${p0Money(point.closingBalanceMinor, currency)}. Выбрать месяц."
                                }.testTag("reports_balance_month_${point.month}")) {
                            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(analyticsMonthName(point.month), style = MaterialTheme.typography.labelLarge)
                                Text(point.month.take(4), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        Text("Пунктир — ноль. Точки — остатки на конец месяца; первая точка показывает начало периода.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
