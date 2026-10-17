package com.moneyflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.remote.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanningScreen(state: PlanningUiState, viewModel: PlanningViewModel) {
    var section by rememberSaveable { mutableStateOf("overview") }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDue by rememberSaveable { mutableStateOf<String?>(null) }
    var editorRevision by rememberSaveable { mutableStateOf(state.revision) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var calendarFilter by rememberSaveable { mutableStateOf("pending") }
    P0ResetOnUserChange(state.session?.user?.id) { editor = null; selectedId = null; selectedDue = null; section = "overview" }
    val open: (String, String?, String?) -> Unit = { sheet, id, due ->
        viewModel.clearMessage(); editorRevision = state.revision; selectedId = id; selectedDue = due; editor = sheet
    }
    val close = { editor = null; selectedId = null; selectedDue = null }
    // The report is deliberately cleared after an acknowledgement. Close at this stable
    // host before its editor disappears, so a refresh cannot reopen a successful draft.
    LaunchedEffect(state.revision) { if (editor != null && state.revision != editorRevision) close() }
    val report = state.report
    val budgets = report?.budgets.orEmpty().filter { showArchived || !it.archived }
    val currentMonth = runCatching { YearMonth.parse(state.month.take(7)) }.getOrDefault(YearMonth.now())
    val occurrences = report?.occurrences.orEmpty().filter { row ->
        val date = runCatching { LocalDate.parse(row.dueDate) }.getOrNull()
        date != null && YearMonth.from(date) == currentMonth && when (calendarFilter) {
            "pending" -> row.status == "pending"
            "posted" -> row.status == "posted"
            else -> true
        }
    }.sortedWith(compareBy<OccurrenceDto> { it.dueDate }.thenBy { it.name }.thenBy { it.scheduleId })

    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("planning_screen"),
        contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { P0Heading("06", "Деньги.\nНаперёд.", "Заранее решите, что важно. Остальное — пространство для жизни.") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("overview" to "Обзор", "budgets" to "Бюджеты", "calendar" to "Календарь").forEach { (id, title) ->
                    FilterChip(section == id, { section = id }, label = { Text(title) }, modifier = Modifier.testTag("planning_tab_$id"))
                }
                TextButton(onClick = viewModel::refresh, enabled = !state.loading && !state.busy,
                    modifier = Modifier.testTag("planning_refresh")) { Text("Обновить") }
            }
        }
        item { PlanningStatus(state, viewModel) }
        when (section) {
            "overview" -> {
                item {
                    val forecast = state.forecast
                    PlanningHero(
                        available = forecast?.takeIf { it.complete }?.freeMinor?.let { planningMoney(it, forecast.baseCurrency) },
                        daily = forecast?.takeIf { it.complete }?.perDayMinor?.let { planningMoney(it, forecast.baseCurrency) },
                        horizon = forecast?.until?.let(::planningDate) ?: "конца периода",
                        days = forecast?.days, estimate = forecast?.let { it.accounts.any { account -> account.currency != it.baseCurrency } } == true,
                        deficit = forecast?.freeMinor?.toLongOrNull()?.let { it < 0 } == true,
                        onSettings = { open("settings", null, null) })
                }
                item { PlanningForecastBreakdown(state, viewModel) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { open("schedule", null, null) }, shape = P0Shape, enabled = state.canWrite,
                            modifier = Modifier.testTag("planning_add_schedule")) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text(" План") }
                        OutlinedButton(onClick = { section = "budgets"; open("budget", null, null) }, shape = P0Shape,
                            enabled = state.canWrite, modifier = Modifier.testTag("planning_add_budget")) { Text("Лимит категории") }
                        TextButton(onClick = { open("schedules", null, null) }, modifier = Modifier.testTag("planning_all_schedules")) { Text("Все планы ↗") }
                    }
                }
                val overdue = report?.occurrences.orEmpty().filter { it.status == "pending" && it.dueDate < (report?.today ?: "") }
                if (overdue.isNotEmpty()) {
                    item { Text("Не забудьте", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
                    item { Text("${report?.overdueCount ?: overdue.size} плановых операций ждут отметки. Они не создают расходов автоматически.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(overdue.take(5), key = { "late_${it.scheduleId}_${it.dueDate}" }) { row ->
                        PlanningOccurrenceCard(row, state, { open("occurrence", row.scheduleId, row.dueDate) })
                    }
                    if (overdue.size > 5) item { TextButton(onClick = { section = "calendar"; viewModel.setMonth(overdue.first().dueDate.take(7)) }) { Text("Все просроченные ↗") } }
                } else if (report != null && report.schedules.none { !it.archived && !it.paused }) item {
                    PlanningEmptyState("01 / НАЧАЛО", "Будущее без сюрпризов.", "Добавьте аренду, подписку или зарплату. Приложение напомнит о плане, а факт вы подтвердите сами.",
                        "Добавить первый план ↗", "planning_first_schedule", state.canWrite) { open("schedule", null, null) }
                }
            }
            "budgets" -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ТЕКУЩИЙ ПЕРИОД", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Лимиты на сегодня.", style = MaterialTheme.typography.headlineSmall)
                        Text("У каждого бюджета свой день начала. Даты текущего периода указаны на карточке.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { open("budget", null, null) }, shape = P0Shape, enabled = state.canWrite,
                            modifier = Modifier.testTag("planning_budget_add")) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text(" Новый лимит") }
                        FilterChip(showArchived, { showArchived = !showArchived }, label = { Text("Архив") }, modifier = Modifier.testTag("planning_budget_archived"))
                    }
                }
                item { Text("Каждая валюта — отдельно. В лимит входят расходы выбранной категории и её подкатегорий, кроме исключённых из отчётов операций.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (budgets.isEmpty() && !state.loading) item {
                    PlanningEmptyState("02 / ЛИМИТЫ", "Дайте тратам границы.", "Лимит помогает заметить перерасход до конца месяца. День начала периода можно выбрать под свой ритм.",
                        "Создать бюджет ↗", "planning_first_budget", state.canWrite) { open("budget", null, null) }
                }
                items(budgets, key = { it.id }) { budget -> PlanningBudgetCard(budget, !state.busy) { open("budget", budget.id, null) } }
            }
            "calendar" -> {
                item { PlanningMonthNavigation(currentMonth, state.loading, viewModel::setMonth) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("pending" to "К оплате", "all" to "Все", "posted" to "Выполнено").forEach { (id, title) ->
                            FilterChip(calendarFilter == id, { calendarFilter = id }, label = { Text(title) }, modifier = Modifier.testTag("planning_calendar_$id"))
                        }
                        TextButton(onClick = { open("schedules", null, null) }, modifier = Modifier.testTag("planning_calendar_schedules")) { Text("Мои планы ↗") }
                    }
                    Button(onClick = { open("schedule", null, null) }, enabled = state.canWrite, shape = P0Shape,
                        modifier = Modifier.testTag("planning_calendar_add")) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text(" Запланировать") }
                }
                if (occurrences.isEmpty() && !state.loading) item { P0Message("В этом месяце нет операций с выбранным статусом. Переключите фильтр или добавьте план.") }
                val grouped = occurrences.groupBy { it.dueDate }
                grouped.forEach { (date, rows) ->
                    item(key = "date_$date") { PlanningCalendarDay(date, date == report?.today) }
                    items(rows, key = { "${it.scheduleId}_${it.dueDate}" }) { row ->
                        PlanningOccurrenceCard(row, state) { open("occurrence", row.scheduleId, row.dueDate) }
                    }
                }
            }
        }
    }
    when (editor) {
        "budget" -> {
            val original = report?.budgets?.firstOrNull { it.id == selectedId }
            if (selectedId == null || original != null) PlanningBudgetEditor(state, viewModel, original, close)
            else PlanningWaitingSheet(state, viewModel, close)
        }
        "schedule" -> {
            val original = report?.schedules?.firstOrNull { it.id == selectedId }
            if (selectedId == null || original != null) PlanningScheduleEditor(state, viewModel, original, close)
            else PlanningWaitingSheet(state, viewModel, close)
        }
        "occurrence" -> {
            val original = report?.occurrences?.firstOrNull { it.scheduleId == selectedId && it.dueDate == selectedDue }
            if (original != null) PlanningOccurrenceEditor(state, viewModel, original, close) { open("schedule", original.scheduleId, null) }
            else PlanningWaitingSheet(state, viewModel, close)
        }
        "settings" -> if (report != null) PlanningSettingsEditor(state, viewModel, report.settings, close) else PlanningWaitingSheet(state, viewModel, close)
        "schedules" -> PlanningSchedulesSheet(state, { open("schedule", it, null) }, { open("schedule", null, null) }, close)
    }
}

@Composable
internal fun PlanningStatus(state: PlanningUiState, viewModel: PlanningViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.offline) P0Message("Нет соединения. Показан сохранённый план${state.receivedAt?.let { " от ${planningReceived(it.toString())}" }.orEmpty()}. Остатки и прогноз могут измениться; подтверждение операций доступно после обновления.")
        if (state.externalBlocked) P0Message("Сначала проверьте результат предыдущего изменения в истории или импорте.")
        PlanningFormStatus(state, viewModel)
        state.notice?.let { P0Message(it, viewModel::clearMessage) }
        if (state.report?.timelineTruncated == true) P0Message("Есть незавершённые события вне загруженной истории. Расчёт неполный: проверьте планы или сузьте период.")
    }
}

@Composable
internal fun PlanningFormStatus(state: PlanningUiState, viewModel: PlanningViewModel) {
    state.error?.let { P0Message(it, viewModel::clearMessage) }
    if (state.recoveryUnavailable) {
        P0Message("Не удалось проверить сохранённое подтверждение. Исходное действие сохранено, новые изменения временно недоступны.")
        OutlinedButton(onClick = viewModel::refresh, enabled = !state.busy, modifier = Modifier.testTag("planning_retry_storage")) { Text("Повторить проверку") }
    } else if ((state.pending != null || state.manualPending) && !state.busy) {
        P0Message("Ответ на предыдущее изменение не получен. Проверьте результат — повтор не создаст дубль.")
        OutlinedButton(onClick = viewModel::retryPending, enabled = !state.externalBlocked && !state.recoveryChecking,
            modifier = Modifier.testTag("planning_retry_pending")) { Text("Проверить результат") }
    }
}

@Composable
private fun PlanningForecastBreakdown(state: PlanningUiState, viewModel: PlanningViewModel) {
    val forecast = state.forecast
    Surface(shape = P0Shape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ПРОЗРАЧНЫЙ РАСЧЁТ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (forecast == null) {
                Text("Загрузите остатки и плановые платежи, чтобы увидеть свободную сумму.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = viewModel::refresh, enabled = !state.loading) { Text("Обновить расчёт") }
            } else {
                PlanningReceiptLine("На выбранных счетах", forecast.balanceMinor?.let { planningMoney(it, forecast.baseCurrency) } ?: "—")
                PlanningReceiptLine("Резерв", "− ${planningMoney(forecast.reserveMinor, forecast.baseCurrency)}")
                PlanningReceiptLine("Обязательные платежи", forecast.mandatoryMinor?.let { "− ${planningMoney(it, forecast.baseCurrency)}" } ?: "—")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PlanningReceiptLine("Свободно", forecast.takeIf { it.complete }?.freeMinor?.let { planningMoney(it, forecast.baseCurrency) } ?: "—", true)
                Text("Ожидаемые доходы не прибавляются к деньгам на счетах. Бюджеты помогают следить за тратами и не вычитаются повторно.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when (forecast.horizonReason) {
                    "primary_income" -> "Период — до ближайшего основного дохода."
                    "custom" -> "Период выбран вами."
                    else -> "Основной доход не задан: расчёт до конца месяца."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                forecast.fetchedAt?.let { Text("Курсы обновлены ${planningReceived(it)} · UTC+5", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (forecast.stale) P0Message("Курсы давно не обновлялись. Валютный прогноз ориентировочный; обновите данные перед решением о расходах.")
                if (!forecast.complete) P0Message(if (forecast.missingCurrencies.isNotEmpty())
                    "Нет курсов для ${forecast.missingCurrencies.joinToString()}. Свободная сумма не показана, чтобы не вводить вас в заблуждение."
                    else "Не все обязательства удалось учесть. Проверьте планы и обновите расчёт.")
                if (forecast.accounts.isEmpty()) Text("В расчёте нет счетов. Выберите карту или наличные в настройках.", style = MaterialTheme.typography.bodyMedium)
                forecast.accounts.forEach { account ->
                    PlanningReceiptLine(account.name, planningMoney(account.balanceMinor, account.currency))
                }
                if (forecast.obligations.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("ЗАРЕЗЕРВИРОВАНО ПОД ПЛАНЫ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    forecast.obligations.take(6).forEach { row ->
                        PlanningReceiptLine("${row.name} · ${planningShortDate(row.dueDate)}", row.convertedAmountMinor?.let { planningMoney(it, forecast.baseCurrency) } ?: "Нет курса")
                    }
                    if (forecast.obligations.size > 6) Text("Ещё ${forecast.obligations.size - 6} платежей учтены в сумме выше.", style = MaterialTheme.typography.bodySmall)
                }
                PlanningDateChoice("До какой даты считать", state.forecastUntil ?: forecast.until, "planning_horizon", !state.loading && !state.busy) {
                    viewModel.setForecastUntil(it)
                }
                if (state.forecastUntil != null) TextButton(onClick = { viewModel.setForecastUntil(null) }, modifier = Modifier.testTag("planning_horizon_auto")) { Text("Автоматически до дохода") }
            }
        }
    }
}

@Composable
private fun PlanningMonthNavigation(month: YearMonth, loading: Boolean, onMonth: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("ПЕРИОД", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onMonth(month.minusMonths(1).toString()) }, enabled = !loading, contentPadding = PaddingValues(12.dp),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Предыдущий месяц" }.testTag("planning_month_previous")) { Text("←") }
            Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru-KZ"))).replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onMonth(month.plusMonths(1).toString()) }, enabled = !loading, contentPadding = PaddingValues(12.dp),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Следующий месяц" }.testTag("planning_month_next")) { Text("→") }
        }
    }
}

@Composable
private fun PlanningBudgetCard(budget: BudgetDto, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = P0Shape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag("planning_budget_${budget.id}")) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(budget.name, style = MaterialTheme.typography.titleLarge)
                    Text("${budget.categoryName} · ${budget.currency}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("↗", style = MaterialTheme.typography.titleLarge)
            }
            PlanningBudgetMeter(budget.spentMinor.toLong(), budget.limitMinor.toLong(), budget.remainingMinor.toLong(), budget.currency)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val end = runCatching { LocalDate.parse(budget.periodTo).minusDays(1).toString() }.getOrDefault(budget.periodTo)
            Text("${planningShortDate(budget.periodFrom)} — ${planningShortDate(end)}${if (budget.archived) " · В архиве" else ""}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (budget.categoryArchived) Text("Категория в архиве", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlanningCalendarDay(date: String, today: Boolean) {
    val parsed = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(parsed.dayOfMonth.toString().padStart(2, '0'), style = MaterialTheme.typography.displaySmall)
        Column(Modifier.weight(1f)) {
            Text(parsed.format(DateTimeFormatter.ofPattern("EEEE", Locale.forLanguageTag("ru-KZ"))).replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.titleMedium)
            Text(if (today) "СЕГОДНЯ" else planningShortDate(date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.width(48.dp), color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun PlanningOccurrenceCard(row: OccurrenceDto, state: PlanningUiState, onClick: () -> Unit) {
    val account = state.accounts.firstOrNull { it.id == row.accountId }
    Surface(shape = P0Shape, color = if (row.status == "posted") MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy, role = Role.Button, onClick = onClick)
            .testTag("planning_occurrence_${row.scheduleId}_${row.dueDate}")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text(if (row.kind == "income") "↙" else "↗", style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleLarge)
                    Text("${planningShortDate(row.dueDate)} · ${account?.name ?: "Счёт"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text((if (row.kind == "income") "+ " else "− ") + planningMoney(row.amountMinor, row.currency), style = MaterialTheme.typography.headlineSmall)
            Text(when {
                row.transactionDeleted -> "Операция удалена · проверьте историю"
                row.status == "posted" -> "✓ Подтверждено"
                row.status == "skipped" -> "Пропущено"
                row.blockedReason != null -> planningBlockedReason(row.blockedReason)
                else -> (if (row.kind == "expense" && row.mandatory) "Обязательный платёж" else if (row.primaryIncome) "Основной доход" else "По плану") + " · подтвердить ↗"
            }, style = MaterialTheme.typography.labelMedium, fontWeight = if (row.status == "pending") FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlanningWaitingSheet(state: PlanningUiState, viewModel: PlanningViewModel, onDismiss: () -> Unit) {
    P0Sheet("planning_waiting", "Возвращаем ваш план.", "Планирование", state.busy, onDismiss) {
        if (state.loading) CircularProgressIndicator(Modifier.size(24.dp))
        else Text("План пока не загружен. Обновите данные, чтобы продолжить с правильной версией.")
        PlanningFormStatus(state, viewModel)
        OutlinedButton(onClick = viewModel::refresh, enabled = !state.loading && !state.busy) { Text("Обновить") }
    }
}

internal fun planningMoney(minor: String, currency: String): String = minor.toLongOrNull()?.let { p0Money(it, currency) } ?: "—"

internal fun planningBlockedReason(reason: String?): String = when (reason) {
    "archived_account" -> "Счёт в архиве — выберите другой в плане"
    "archived_category" -> "Категория в архиве — измените план"
    "paused" -> "План на паузе"
    "archived_schedule" -> "План в архиве"
    else -> "Проверьте настройки плана"
}

private fun planningReceived(stamp: String): String = runCatching {
    java.time.Instant.parse(stamp).atOffset(java.time.ZoneOffset.ofHours(5)).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
}.getOrDefault(stamp)
