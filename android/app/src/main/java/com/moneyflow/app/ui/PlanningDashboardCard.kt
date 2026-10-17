package com.moneyflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun PlanningDashboardCard(state: PlanningUiState?, onOpen: () -> Unit) {
    val forecast = state?.forecast
    val total = forecast?.takeIf { it.complete }?.freeMinor
    val estimate = forecast?.let { f -> f.accounts.any { it.currency != f.baseCurrency } || f.obligations.any { it.currency != f.baseCurrency } } == true
    Surface(onClick = onOpen, shape = P0Shape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("dashboard_planning")) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (total?.startsWith("-") == true) "ДЕФИЦИТ ПО ПЛАНУ" else "МОЖНО ПОТРАТИТЬ", style = MaterialTheme.typography.labelMedium)
                Text("↗", style = MaterialTheme.typography.titleLarge)
            }
            Text(total?.let { (if (estimate) "≈ " else "") + Money.format(it, forecast.baseCurrency) } ?: "Деньги. Наперёд.",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            val subtitle = when {
                state?.offline == true -> "Сохранённый расчёт · обновите данные перед планированием"
                forecast != null && !forecast.complete -> "Не хватает данных для полного расчёта. Откройте планирование."
                forecast != null -> "До ${LocalDate.parse(forecast.until).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))} включительно · после резерва и обязательных платежей"
                else -> "Бюджеты, регулярные платежи и свободный остаток"
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (forecast?.stale == true) Text("Оценка по сохранённым курсам", style = MaterialTheme.typography.labelSmall)
        }
    }
}
