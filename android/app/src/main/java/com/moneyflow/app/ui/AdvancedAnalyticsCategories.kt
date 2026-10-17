package com.moneyflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneyflow.app.data.domain.AdvancedAnalyticsCategory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private fun advancedCategoryShade(index: Int, surface: Color, ink: Color): Color {
    val shades = listOf(.92f, .48f, .74f, .60f, .84f, .68f)
    return lerp(surface, ink, shades[index % shades.size])
}

@Composable
internal fun AdvancedCategoryRing(categories: List<AdvancedAnalyticsCategory>, total: Long, currency: String, kindTitle: String,
    onSelect: (AdvancedAnalyticsCategory) -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val text = rememberTextMeasurer()
    val style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(236.dp).testTag("reports_category_chart").semantics {
                contentDescription = "$kindTitle по категориям. Всего ${p0Money(total, currency)}. Номера сегментов соответствуют списку под диаграммой; маленькие доли также есть в списке."
            }.pointerInput(categories, total) {
                detectTapGestures { tap ->
                    val dx = tap.x - size.width / 2f
                    val dy = tap.y - size.height / 2f
                    val radius = hypot(dx, dy)
                    val outer = minOf(size.width, size.height) / 2f
                    if (radius >= outer * .65f && radius <= outer) {
                        val angle = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 450.0) % 360.0).toFloat()
                        var end = 0f
                        categories.firstOrNull { category ->
                            end += analyticsPosition(category.amountMinor, 0, total) * 360f
                            angle <= end
                        }?.let(onSelect)
                    }
                }
            }) {
                val stroke = size.minDimension * .15f
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                var start = -90f
                categories.forEachIndexed { index, category ->
                    val sweep = analyticsPosition(category.amountMinor, 0, total) * 360f
                    drawArc(advancedCategoryShade(index, surface, ink), start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    if (categories.size > 1) {
                        val radians = Math.toRadians(start.toDouble())
                        val inner = size.minDimension / 2 - stroke
                        val outer = size.minDimension / 2
                        drawLine(surface, Offset(center.x + cos(radians).toFloat() * inner, center.y + sin(radians).toFloat() * inner),
                            Offset(center.x + cos(radians).toFloat() * outer, center.y + sin(radians).toFloat() * outer), 2.dp.toPx())
                    }
                    if (sweep >= 20f) {
                        val radians = Math.toRadians((start + sweep / 2).toDouble())
                        val radius = size.minDimension / 2 - stroke / 2
                        val labelCenter = Offset(center.x + cos(radians).toFloat() * radius, center.y + sin(radians).toFloat() * radius)
                        val label = text.measure((index + 1).toString(), style)
                        val circle = maxOf(10.dp.toPx(), maxOf(label.size.width, label.size.height) / 2f + 2.dp.toPx())
                        drawCircle(surface, circle, labelCenter)
                        drawText(label, topLeft = Offset(labelCenter.x - label.size.width / 2f, labelCenter.y - label.size.height / 2f))
                    }
                    start += sweep
                }
            }
            Column(Modifier.widthIn(max = 148.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(kindTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(categories.size.toString(), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                Text("категорий", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(p0Money(total, currency), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("reports_category_total"))
        Text("Нажмите сегмент или строку ниже, чтобы перейти к деталям. Суммы за прошлый месяц показаны для сравнения.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun AdvancedCategoryRow(category: AdvancedAnalyticsCategory, number: Int, total: Long, currency: String,
    canDrill: Boolean, onClick: () -> Unit, onPrevious: (() -> Unit)?) {
    val percent = category.percentage(total).toPlainString().replace('.', ',')
    Surface(shape = P0Shape, color = if (category.amountMinor == 0L) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) {
                contentDescription = "$number. ${category.name}. ${p0Money(category.amountMinor, currency)}, $percent процента. В прошлом месяце ${p0Money(category.previousAmountMinor, currency)}. ${if (canDrill) "Открыть подкатегории" else "Открыть операции"}."
            }.testTag("reports_category_${category.categoryId ?: "uncategorized"}_${if (category.name == "Без подкатегории") "direct" else "total"}"),
                verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(36.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(number.toString(), style = MaterialTheme.typography.labelLarge) }
                    }
                    Text(category.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Text(if (canDrill) "↓" else "↗", style = MaterialTheme.typography.titleLarge)
                }
                Text(p0Money(category.amountMinor, currency), style = MaterialTheme.typography.headlineSmall)
                Text("$percent % от ${if (category.name == "Без подкатегории") "категории" else "выбранного итога"}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Ранее ${p0Money(category.previousAmountMinor, currency)} · изменение ${analyticsSignedMoney(category.deltaMinor, currency)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (category.previousAmountMinor == 0L && category.amountMinor > 0L) Text("В прошлом месяце этой статьи не было", style = MaterialTheme.typography.bodySmall)
            }
            if (onPrevious != null) TextButton(onClick = onPrevious, modifier = Modifier.testTag("reports_category_previous_${category.categoryId ?: "uncategorized"}")) {
                Text("Операции прошлого месяца ↗")
            }
        }
    }
}
