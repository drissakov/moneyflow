package com.moneyflow.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneyflow.app.data.domain.AnalyticsCategory
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.SupportedCurrency
import java.math.BigInteger
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private fun categoryKey(category: AnalyticsCategory): String = category.categoryId ?: "uncategorized"

/** Stable neutral shades; numbers in the chart and legend identify categories independently of shade. */
private fun categoryColors(categories: List<AnalyticsCategory>, surface: Color, foreground: Color): Map<String, Color> {
    val keys = categories.map(::categoryKey).distinct().sorted()
    val shades = listOf(0.90f, 0.50f, 0.72f, 0.58f, 0.82f, 0.66f, 0.94f)
    return keys.mapIndexed { index, key ->
        key to lerp(surface, foreground, shades[index % shades.size])
    }.toMap()
}

private fun drilldownCategories(root: AnalyticsCategory): List<AnalyticsCategory> {
    val childTotal = root.children.fold(BigInteger.ZERO) { sum, child -> sum + BigInteger.valueOf(child.amountMinor) }
    val direct = BigInteger.valueOf(root.amountMinor) - childTotal
    return (root.children + if (direct.signum() > 0) listOf(
        AnalyticsCategory(root.categoryId, "Без подкатегории", direct.toString().toLong()),
    ) else emptyList()).filter { it.amountMinor > 0 }.sortedByDescending { it.amountMinor }
}

@Composable
internal fun AnalyticsScreen(state: MoneyFlowUiState, viewModel: MoneyFlowViewModel) {
    var kind by rememberSaveable { mutableStateOf("expense") }
    var selectedRootId by rememberSaveable(state.analyticsMonth, state.analyticsCurrency, kind) { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshAnalytics() }
    val month = YearMonth.parse(state.analyticsMonth)
    val report = state.analytics?.takeIf { it.month == state.analyticsMonth && it.currency == state.analyticsCurrency }
    val roots = (if (kind == "expense") report?.expenses else report?.income).orEmpty()
        .filter { it.amountMinor > 0 }.sortedByDescending { it.amountMinor }
    val selectedRoot = roots.firstOrNull { it.categoryId != null && it.categoryId == selectedRootId }
    val slices = if (selectedRoot != null) drilldownCategories(selectedRoot) else roots
    val colors = categoryColors(slices, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
    val total = selectedRoot?.amountMinor ?: if (kind == "expense") report?.expenseMinor ?: 0 else report?.incomeMinor ?: 0
    val kindTitle = if (kind == "expense") "Расходы" else "Доходы"
    LazyColumn(
        Modifier.widthIn(max = 620.dp).fillMaxSize().testTag("analytics_screen"),
        contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                P0Heading("04", "Картина месяца.", "Доходы, расходы и то, что за ними стоит.")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.setAnalyticsMonth(month.minusMonths(1).toString()) },
                        enabled = month > YearMonth.of(1, 1),
                        modifier = Modifier.testTag("analytics_previous_month").semantics { contentDescription = "Предыдущий месяц" }) {
                        Text("‹", style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru-RU")))
                        .replaceFirstChar { it.titlecase(Locale.forLanguageTag("ru-RU")) },
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).testTag("analytics_month"))
                    TextButton(onClick = { viewModel.setAnalyticsMonth(month.plusMonths(1).toString()) },
                        enabled = month < YearMonth.of(9999, 12),
                        modifier = Modifier.testTag("analytics_next_month").semantics { contentDescription = "Следующий месяц" }) {
                        Text("›", style = MaterialTheme.typography.headlineSmall)
                    }
                }
                CurrencySelector("Валюта операций", SupportedCurrency.fromCode(state.analyticsCurrency),
                    { viewModel.setAnalyticsCurrency(it.code) }, modifier = Modifier.fillMaxWidth().testTag("analytics_currency"),
                    tagPrefix = "analytics_currency")
                Text("Все счета в ${state.analyticsCurrency} · месяц по часовому поясу ${state.analyticsTimezone}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.analyticsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("analytics_loading")) }
        state.analyticsError?.let { error -> item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.testTag("analytics_error"))
                    TextButton(onClick = viewModel::refreshAnalytics, enabled = !state.analyticsLoading) { Text("Повторить загрузку") }
                }
            }
        } }
        if (report != null) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TotalCard("Доходы", report.incomeMinor, report.currency, true, Modifier.weight(1f))
                    TotalCard("Расходы", report.expenseMinor, report.currency, false, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "expense", onClick = { kind = "expense" }, label = { Text("Расходы") },
                        modifier = Modifier.testTag("analytics_expense"))
                    FilterChip(kind == "income", onClick = { kind = "income" }, label = { Text("Доходы") },
                        modifier = Modifier.testTag("analytics_income"))
                }
            }
            if (total == 0L || slices.isEmpty()) item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${if (kind == "expense") "Расходов" else "Доходов"} пока нет", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("analytics_empty"))
                        Text("Добавьте операции в ${report.currency} за выбранный месяц, чтобы увидеть распределение.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                item {
                    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            if (selectedRoot != null) {
                                TextButton(onClick = { selectedRootId = null }, modifier = Modifier.align(Alignment.Start).testTag("analytics_back")) {
                                    Text("‹ Все категории")
                                }
                                Text(selectedRoot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            } else Text(if (kind == "expense") "На что уходят деньги" else "Источники дохода",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            CategoryDonut(slices, colors, total, report.currency, kindTitle)
                            Text(if (selectedRoot == null && kind == "expense") "Нажмите на категорию в списке, чтобы увидеть подкатегории."
                                else if (selectedRoot != null) "«Без подкатегории» — операции, записанные напрямую в эту категорию."
                                else "Доля каждого источника в доходах за месяц.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center)
                        }
                    }
                }
                itemsIndexed(slices, key = { _, category -> "${category.categoryId}:${category.name}" }) { index, category ->
                    val canDrilldown = selectedRoot == null && kind == "expense" && category.children.isNotEmpty() && category.categoryId != null
                    CategoryLegendRow(category, index + 1, colors.getValue(categoryKey(category)), total, report.currency, canDrilldown,
                        onClick = { if (canDrilldown) selectedRootId = category.categoryId })
                }
            }
            item {
                Text("Итоги рассчитаны по всем операциям за месяц. Начальные остатки счетов в доходы не входят.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = viewModel::refreshAnalytics, enabled = !state.analyticsLoading, modifier = Modifier.testTag("analytics_refresh")) {
                    Text("Обновить аналитику")
                }
            }
        } else if (!state.analyticsLoading && state.analyticsError == null) item {
            Text("Загружаем отчёт за выбранный месяц…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TotalCard(title: String, amount: Long, currency: String, income: Boolean, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Money.format(amount, currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(if (income) "analytics_income_total" else "analytics_expense_total"))
        }
    }
}

@Composable
private fun CategoryDonut(categories: List<AnalyticsCategory>, colors: Map<String, Color>, total: Long, currency: String, kindTitle: String) {
    val textMeasurer = rememberTextMeasurer()
    val surface = MaterialTheme.colorScheme.surface
    val foreground = MaterialTheme.colorScheme.onSurface
    val numberStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = foreground)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(240.dp).testTag("analytics_chart").semantics {
            contentDescription = "$kindTitle по категориям. Всего ${Money.format(total, currency)}. Номера сегментов соответствуют списку под диаграммой. Малые сегменты указаны в списке."
        }) {
            val strokeWidth = size.minDimension * 0.14f
            val inset = strokeWidth / 2
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            var startAngle = -90f
            categories.forEachIndexed { index, category ->
                val sweep = (category.amountMinor.toDouble() / total.toDouble() * 360.0).toFloat()
                drawArc(colors.getValue(categoryKey(category)), startAngle, sweep, false, Offset(inset, inset), arcSize, style = Stroke(strokeWidth))
                // Thin boundaries distinguish adjacent neutral shades without changing the data.
                if (categories.size > 1) {
                    val boundary = Math.toRadians(startAngle.toDouble())
                    val innerRadius = size.minDimension / 2 - strokeWidth
                    val outerRadius = size.minDimension / 2
                    drawLine(surface,
                        Offset(center.x + cos(boundary).toFloat() * innerRadius, center.y + sin(boundary).toFloat() * innerRadius),
                        Offset(center.x + cos(boundary).toFloat() * outerRadius, center.y + sin(boundary).toFloat() * outerRadius),
                        strokeWidth = 2.dp.toPx())
                }
                if (sweep >= 20f) {
                    val angle = Math.toRadians((startAngle + sweep / 2).toDouble())
                    val radius = size.minDimension / 2 - strokeWidth / 2
                    val labelCenter = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
                    val label = textMeasurer.measure((index + 1).toString(), numberStyle)
                    val labelRadius = maxOf(10.dp.toPx(), maxOf(label.size.width, label.size.height) / 2f + 2.dp.toPx())
                    drawCircle(surface, radius = labelRadius, center = labelCenter)
                    drawText(label, topLeft = Offset(labelCenter.x - label.size.width / 2f, labelCenter.y - label.size.height / 2f))
                }
                startAngle += sweep
            }
        }
        Column(Modifier.widthIn(max = 158.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(kindTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Money.format(total, currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CategoryLegendRow(category: AnalyticsCategory, number: Int, color: Color, total: Long, currency: String, canDrilldown: Boolean, onClick: () -> Unit) {
    val amount = Money.format(category.amountMinor, currency)
    val percent = "${category.percentage(total).toPlainString().replace('.', ',')} %"
    Card(onClick = onClick, enabled = canDrilldown, shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface, disabledContentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("analytics_category_${category.categoryId ?: "uncategorized"}")
            .semantics(mergeDescendants = true) {
                contentDescription = "$number. ${category.name}: $amount, $percent${if (canDrilldown) ". Открыть подкатегории" else ""}"
            }) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(category.name, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                    Text(percent, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 120.dp))
            if (canDrilldown) Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}
