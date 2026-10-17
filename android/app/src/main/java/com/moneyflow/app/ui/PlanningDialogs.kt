package com.moneyflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.SupportedCurrency
import com.moneyflow.app.data.remote.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
internal fun PlanningBudgetEditor(state: PlanningUiState, viewModel: PlanningViewModel, original: BudgetDto?, onDismiss: () -> Unit) {
    val baseVersion = rememberSaveable(original?.id) { original?.version ?: 0L }
    var name by rememberSaveable(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var categoryId by rememberSaveable(original?.id) { mutableStateOf(original?.categoryId) }
    var currency by rememberSaveable(original?.id) { mutableStateOf(original?.currency ?: state.report?.settings?.baseCurrency ?: "KZT") }
    var limit by rememberSaveable(original?.id) { mutableStateOf(original?.let { p0Input(it.limitMinor.toLong(), it.currency) }.orEmpty()) }
    var day by rememberSaveable(original?.id) { mutableStateOf((original?.periodStartDay ?: 1).toString()) }
    var choosingCurrency by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val revision = rememberSaveable(original?.id) { state.revision }
    LaunchedEffect(state.revision) { if (state.revision != revision) onDismiss() }
    P0Sheet("planning_budget_editor", if (original == null) "У трат есть предел." else "Свой ритм расходов.",
        "Бюджет / ${if (original == null) "добавить" else "изменить"}", state.busy, onDismiss) {
        if (original?.archived == true) P0Message("Бюджет в архиве. Его можно вернуть, когда снова понадобится лимит.")
        OutlinedTextField(name, { name = it.take(100) }, label = { Text("Название бюджета") }, singleLine = true,
            enabled = state.canWrite, shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_budget_name"))
        CategorySelector(state.categories.filter { !it.archived || it.id == categoryId }, "expense", categoryId, { selected ->
            categoryId = selected
            if (name.isBlank()) name = state.categories.firstOrNull { it.id == selected }?.name.orEmpty()
        }, enabled = state.canWrite, tagPrefix = "planning_budget_category")
        Text("Учитываются расходы этой категории и всех её подкатегорий в выбранной валюте. Перекрывающиеся лимиты не дублируются.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        P0Choice("Валюта лимита", currency, "planning_budget_currency", state.canWrite) { choosingCurrency = true }
        OutlinedTextField(limit, { limit = it }, label = { Text("Лимит · $currency") }, singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            enabled = state.canWrite, shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_budget_limit"))
        OutlinedTextField(day, { day = it.take(2) }, label = { Text("День начала периода") }, singleLine = true,
            supportingText = { Text("От 1 до 31. В коротком месяце — последний день.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = state.canWrite,
            shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_budget_start_day"))
        Text("Бюджет не блокирует операции и не списывает деньги. Он сравнивает ваш план с фактическими расходами.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        localError?.let { P0Message(it) }; PlanningFormStatus(state, viewModel)
        P0Submit(if (original == null) "Создать бюджет ↗" else "Сохранить бюджет ↗", "planning_budget_save", state.busy,
            state.canWrite && name.isNotBlank() && categoryId != null && limit.isNotBlank()) {
            runCatching {
                val startDay = day.toIntOrNull()?.takeIf { it in 1..31 } ?: throw IllegalArgumentException("Выберите день от 1 до 31")
                val minor = Money.parseMinor(limit, SupportedCurrency.fromCode(currency), positive = true)
                viewModel.saveBudget(original?.copy(version = baseVersion), name.trim(), requireNotNull(categoryId), currency, minor, startDay, original?.archived ?: false)
            }.onFailure { localError = it.message ?: "Проверьте бюджет" }
        }
        if (original != null) OutlinedButton(onClick = {
            viewModel.saveBudget(original.copy(version = baseVersion), original.name, original.categoryId, original.currency,
                original.limitMinor.toLong(), original.periodStartDay, !original.archived)
        }, enabled = state.canWrite, shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_budget_archive")) {
            Text(if (original.archived) "Вернуть из архива" else "Убрать в архив")
        }
    }
    if (choosingCurrency) PlanningCurrencyPicker(currency, "planning_budget_currencies", { choosingCurrency = false }) {
        if (currency != it) limit = ""
        currency = it; choosingCurrency = false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanningScheduleEditor(state: PlanningUiState, viewModel: PlanningViewModel, original: ScheduleDto?, onDismiss: () -> Unit) {
    val baseVersion = rememberSaveable(original?.id) { original?.version ?: 0L }
    var name by rememberSaveable(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var accountId by rememberSaveable(original?.id) { mutableStateOf(original?.accountId ?: state.accounts.firstOrNull { !it.archived }?.id.orEmpty()) }
    var categoryId by rememberSaveable(original?.id) { mutableStateOf(original?.categoryId) }
    var kind by rememberSaveable(original?.id) { mutableStateOf(original?.kind ?: "expense") }
    var amount by rememberSaveable(original?.id) { mutableStateOf(original?.let { p0Input(it.amountMinor.toLong(), it.currency) }.orEmpty()) }
    var note by rememberSaveable(original?.id) { mutableStateOf(original?.note.orEmpty()) }
    var date by rememberSaveable(original?.id) { mutableStateOf(original?.startDate ?: state.report?.today ?: LocalDate.now(ZoneOffset.ofHours(5)).toString()) }
    var frequency by rememberSaveable(original?.id) { mutableStateOf(original?.frequency ?: "monthly") }
    var interval by rememberSaveable(original?.id) { mutableStateOf((original?.interval ?: 1).toString()) }
    var hasEnd by rememberSaveable(original?.id) { mutableStateOf(original?.endDate != null) }
    var endDate by rememberSaveable(original?.id) { mutableStateOf(original?.endDate ?: date) }
    var mandatory by rememberSaveable(original?.id) { mutableStateOf(original?.mandatory ?: true) }
    var primaryIncome by rememberSaveable(original?.id) { mutableStateOf(original?.primaryIncome ?: false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val account = state.accounts.firstOrNull { it.id == accountId }
    val locked = original?.scheduleLocked == true
    val revision = rememberSaveable(original?.id) { state.revision }
    LaunchedEffect(state.revision) { if (state.revision != revision) onDismiss() }
    val saveOriginalFlags: (Boolean, Boolean) -> Unit = { paused, archived ->
        if (original != null) viewModel.saveSchedule(original.copy(version = baseVersion), original.name, original.accountId, original.categoryId,
            original.kind, original.amountMinor.toLong(), original.note, original.startDate, original.endDate, original.frequency,
            original.interval, original.mandatory, original.primaryIncome, paused, archived)
    }
    P0Sheet("planning_schedule_editor", if (original == null) "Назначьте день." else "План под вашу жизнь.",
        "План / ${if (original == null) "добавить" else "изменить"}", state.busy, onDismiss) {
        Text("План — напоминание. Реальный доход или расход появится только после вашего подтверждения.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        original?.blockedReason?.let { P0Message(planningBlockedReason(it)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("expense" to "↗ Расход", "income" to "↙ Доход").forEach { (id, title) ->
                FilterChip(kind == id, { kind = id; categoryId = null; mandatory = id == "expense"; primaryIncome = false },
                    label = { Text(title) }, enabled = state.canWrite, modifier = Modifier.testTag("planning_schedule_kind_$id"))
            }
        }
        OutlinedTextField(name, { name = it.take(100) }, label = { Text(if (kind == "income") "Например, зарплата" else "Например, аренда") }, singleLine = true,
            enabled = state.canWrite, shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_schedule_name"))
        P0Choice(if (kind == "income") "Куда поступят деньги" else "С какого счёта", account?.let { "${it.name} · ${it.currency}" } ?: "Выберите счёт",
            "planning_schedule_account", state.canWrite) { picker = "account" }
        OutlinedTextField(amount, { amount = it }, label = { Text("Плановая сумма · ${account?.currency.orEmpty()}") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), textStyle = MaterialTheme.typography.headlineMedium,
            enabled = state.canWrite, shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_schedule_amount"))
        CategorySelector(state.categories.filter { !it.archived || it.id == categoryId }, kind, categoryId, { categoryId = it },
            enabled = state.canWrite, tagPrefix = "planning_schedule_category")
        PlanningDateChoice(if (frequency == "once") "Дата" else "Первая дата", date, "planning_schedule_date", state.canWrite && !locked) { date = it }
        P0Choice("Повторение", planningRecurrence(frequency), "planning_schedule_frequency", state.canWrite && !locked) { picker = "frequency" }
        if (frequency != "once") {
            OutlinedTextField(interval, { interval = it.take(2) }, label = { Text("Интервал повторения") }, singleLine = true,
                supportingText = { Text(when (frequency) { "weekly" -> "Каждые N недель, от 1 до 12"; "yearly" -> "Каждые N лет, от 1 до 12"; else -> "Каждые N месяцев, от 1 до 12" }) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = state.canWrite && !locked,
                shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_schedule_interval"))
            if (frequency in listOf("monthly", "yearly")) Text("Если выбранной даты нет в месяце, платёж попадёт на последний день. Следующий месяц снова использует исходную дату.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PlanningSwitch("Дата окончания", "Без неё план продолжается до паузы или архива", hasEnd, state.canWrite,
                "planning_schedule_has_end") { hasEnd = it }
            if (hasEnd) PlanningDateChoice("Последняя дата", endDate, "planning_schedule_end_date", state.canWrite) { endDate = it }
        }
        if (locked) P0Message("В этом плане уже есть подтверждения или пропуски. Первая дата и ритм повторения закреплены. Дату окончания можно изменить; для другого ритма создайте новый план.")
        if (kind == "expense") PlanningSwitch("Обязательный платёж", "Заранее вычитать из суммы «Можно потратить»", mandatory, state.canWrite,
            "planning_schedule_mandatory") { mandatory = it }
        else PlanningSwitch("Основной доход", "Считать свободные деньги до ближайшей даты этого дохода", primaryIncome, state.canWrite,
            "planning_schedule_primary_income") { primaryIncome = it }
        OutlinedTextField(note, { note = it.take(500) }, label = { Text("Заметка") }, minLines = 2, maxLines = 4,
            enabled = state.canWrite, shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_schedule_note"))
        localError?.let { P0Message(it) }; PlanningFormStatus(state, viewModel)
        P0Submit(if (original == null) "Создать план ↗" else "Сохранить план ↗", "planning_schedule_save", state.busy,
            state.canWrite && name.isNotBlank() && amount.isNotBlank() && account != null && !account.archived) {
            runCatching {
                val selected = requireNotNull(account) { "Выберите счёт" }
                val minor = Money.parseMinor(amount, SupportedCurrency.fromCode(selected.currency), positive = true)
                val repeat = if (frequency == "once") original?.takeIf { it.frequency == "once" }?.interval ?: 1 else interval.toIntOrNull()?.takeIf { it in 1..12 }
                    ?: throw IllegalArgumentException("Интервал — от 1 до 12")
                LocalDate.parse(date)
                val end = if (frequency != "once" && hasEnd) endDate.also {
                    require(LocalDate.parse(it) >= LocalDate.parse(date)) { "Дата окончания не может быть раньше первой даты" }
                } else if (frequency == "once") original?.takeIf { it.frequency == "once" }?.endDate else null
                viewModel.saveSchedule(original?.copy(version = baseVersion), name.trim(), accountId, categoryId, kind, minor, note.trim(), date,
                    end, frequency, repeat, kind == "expense" && mandatory, kind == "income" && primaryIncome, original?.paused ?: false, original?.archived ?: false)
            }.onFailure { localError = it.message ?: "Проверьте план" }
        }
        if (original != null) {
            if (!original.archived) OutlinedButton(onClick = { saveOriginalFlags(!original.paused, original.archived) }, enabled = state.canWrite,
                shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_schedule_pause")) { Text(if (original.paused) "Продолжить план" else "Поставить на паузу") }
            OutlinedButton(onClick = { saveOriginalFlags(original.paused, !original.archived) }, enabled = state.canWrite,
                shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_schedule_archive")) { Text(if (original.archived) "Вернуть из архива" else "Убрать в архив") }
            Text("Пауза и архив сохраняют историю подтверждений. Прошлые операции остаются в истории счетов.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    when (picker) {
        "account" -> P0OptionsSheet("Счёт для плана", state.accounts.filter { !it.archived || it.id == accountId }.map {
            P0Option(it.id, it.name, "${it.currency}${if (it.archived) " · архив" else ""}") }, accountId, "planning_schedule_accounts", { picker = null }) { id ->
            if (account?.currency != state.accounts.firstOrNull { it.id == id }?.currency) amount = ""
            accountId = id; picker = null
        }
        "frequency" -> P0OptionsSheet("Ритм плана", listOf("once", "weekly", "monthly", "yearly").map { P0Option(it, planningRecurrence(it)) },
            frequency, "planning_schedule_frequencies", { picker = null }) { frequency = it; picker = null }
    }
}

@Composable
internal fun PlanningOccurrenceEditor(state: PlanningUiState, viewModel: PlanningViewModel, original: OccurrenceDto,
    onDismiss: () -> Unit, onEditSchedule: () -> Unit) {
    val identity = "${original.scheduleId}/${original.dueDate}"
    val baseVersion = rememberSaveable(identity) { original.version }
    val baseScheduleVersion = rememberSaveable(identity) { original.scheduleVersion }
    var amount by rememberSaveable(identity) { mutableStateOf(p0Input(original.amountMinor.toLong(), original.currency)) }
    var note by rememberSaveable(identity) { mutableStateOf(original.note) }
    var categoryId by rememberSaveable(identity) { mutableStateOf(original.categoryId) }
    var date by rememberSaveable(identity) { mutableStateOf(state.report?.today ?: LocalDate.now(ZoneOffset.ofHours(5)).toString()) }
    var time by rememberSaveable(identity) { mutableStateOf(LocalTime.now(ZoneOffset.ofHours(5)).format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var confirmSkip by rememberSaveable(identity) { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val revision = rememberSaveable(identity) { state.revision }
    LaunchedEffect(state.revision) { if (state.revision != revision) onDismiss() }
    val account = state.accounts.firstOrNull { it.id == original.accountId }
    val snapshot = original.copy(version = baseVersion, scheduleVersion = baseScheduleVersion)
    val editable = state.canWrite && original.status == "pending" && original.blockedReason == null && account?.archived == false
    if (confirmSkip) {
        P0Sheet("planning_skip_confirm", if (original.status == "skipped") "Вернуть в план?" else "Пропустить этот раз?", "Календарь / ${planningShortDate(original.dueDate)}", state.busy,
            { confirmSkip = false }) {
            Text(if (original.status == "skipped") "Платёж снова будет ждать подтверждения и учитываться в прогнозе, если он обязательный."
                else "Операция не появится в истории. Обязательный платёж перестанет уменьшать свободную сумму; следующие повторения сохранятся.")
            PlanningFormStatus(state, viewModel)
            P0Submit(if (original.status == "skipped") "Вернуть в план" else "Пропустить", "planning_skip_confirm_save", state.busy, state.canWrite) {
                if (original.status == "skipped") viewModel.unskipOccurrence(snapshot) else viewModel.skipOccurrence(snapshot)
            }
        }
        return
    }
    P0Sheet("planning_occurrence_editor", original.name, "Календарь / ${planningShortDate(original.dueDate)}", state.busy, onDismiss) {
        Text("${if (original.kind == "income") "Доход" else "Расход"} · ${account?.name ?: "Счёт"} · ${original.currency}",
            style = MaterialTheme.typography.titleMedium)
        if (original.status == "posted") {
            P0Message(if (original.transactionDeleted) "Фактическая операция удалена. Её можно восстановить через историю. Подтвердить этот план повторно нельзя."
                else "Эта операция уже добавлена в историю и учтена в остатке. Изменить сумму, дату или удалить её можно в истории.")
        } else if (original.status == "skipped") {
            P0Message("Это повторение пропущено и не влияет на свободную сумму. Следующие повторения сохраняются.")
        } else {
            original.blockedReason?.let { P0Message(planningBlockedReason(it)) }
            Text("Плановая сумма — ${planningMoney(original.amountMinor, original.currency)}. Укажите фактические данные: после подтверждения изменится остаток счёта.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(amount, { amount = it }, label = { Text("Фактическая сумма · ${original.currency}") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), textStyle = MaterialTheme.typography.headlineMedium,
                enabled = editable, shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_occurrence_amount"))
            P0DateTimeFields(date, time, { date = it }, { time = it }, editable, "planning_occurrence")
            Text("Дата и время — по Казахстану, UTC+5.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CategorySelector(state.categories.filter { !it.archived || it.id == categoryId }, original.kind, categoryId, { categoryId = it },
                enabled = editable, tagPrefix = "planning_occurrence_category")
            OutlinedTextField(note, { note = it.take(500) }, label = { Text("Описание фактической операции") }, minLines = 2, maxLines = 4,
                enabled = editable, shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_occurrence_note"))
            localError?.let { P0Message(it) }
            P0Submit(if (original.kind == "income") "Подтвердить поступление ↙" else "Подтвердить оплату ↗", "planning_occurrence_post", state.busy,
                editable && amount.isNotBlank()) {
                runCatching {
                    val minor = Money.parseMinor(amount, SupportedCurrency.fromCode(original.currency), positive = true)
                    viewModel.postOccurrence(snapshot, minor, planningInstant(date, time), note.trim(), categoryId)
                }.onFailure { localError = it.message ?: "Проверьте фактические данные" }
            }
        }
        PlanningFormStatus(state, viewModel)
        if (original.status in listOf("pending", "skipped")) OutlinedButton(onClick = { confirmSkip = true }, enabled = state.canWrite,
            shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("planning_occurrence_skip")) { Text(if (original.status == "skipped") "Вернуть в план" else "Пропустить этот раз") }
        TextButton(onClick = onEditSchedule, enabled = !state.busy, modifier = Modifier.testTag("planning_occurrence_edit_plan")) { Text("Изменить весь план ↗") }
    }
}

@Composable
internal fun PlanningSettingsEditor(state: PlanningUiState, viewModel: PlanningViewModel, original: PlanningSettingsDto, onDismiss: () -> Unit) {
    val baseVersion = rememberSaveable { original.version }
    var currency by rememberSaveable { mutableStateOf(original.baseCurrency) }
    var reserve by rememberSaveable { mutableStateOf(p0Input(original.reserveMinor.toLong(), original.baseCurrency)) }
    var automatic by rememberSaveable { mutableStateOf(original.accountIds.isEmpty()) }
    var selectedIds by rememberSaveable { mutableStateOf(original.accountIds.toList()) }
    var choosingCurrency by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val revision = rememberSaveable { state.revision }
    LaunchedEffect(state.revision) { if (state.revision != revision) onDismiss() }
    val eligible = state.accounts.filter { !it.archived && it.type in listOf("card", "cash") }
    P0Sheet("planning_settings_editor", "Свободно —\nпо вашим правилам.", "Планирование / расчёт", state.busy, onDismiss) {
        Text("Остатки на выбранных счетах − резерв − обязательные платежи до выбранной даты. Ожидаемые доходы в остаток не добавляются.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        P0Choice("Валюта расчёта и резерва", currency, "planning_settings_currency", state.canWrite) { choosingCurrency = true }
        OutlinedTextField(reserve, { reserve = it }, label = { Text("Неприкосновенный резерв · $currency") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = state.canWrite,
            supportingText = { Text("Сумма, которую вы пока не хотите тратить") }, shape = P0Shape,
            modifier = Modifier.fillMaxWidth().testTag("planning_settings_reserve"))
        PlanningSwitch("Выбирать счета автоматически", "Все активные карты и наличные, включённые в общий баланс", automatic, state.canWrite,
            "planning_settings_automatic") { enabled ->
            automatic = enabled
            if (!enabled && selectedIds.isEmpty()) selectedIds = eligible.filter { it.includeInBalance }.map { it.id }
        }
        if (!automatic) {
            Text("Счета для повседневных расходов", style = MaterialTheme.typography.titleMedium)
            if (eligible.isEmpty()) P0Message("Нет активных карт или наличных. Добавьте такой счёт в разделе «Счета».")
            (eligible + state.accounts.filter { it.id in selectedIds && it !in eligible }).distinctBy { it.id }.forEach { account ->
                val available = account in eligible
                Row(Modifier.fillMaxWidth().toggleable(value = account.id in selectedIds,
                    enabled = state.canWrite && (available || account.id in selectedIds), role = Role.Checkbox) { checked ->
                    selectedIds = if (!checked) selectedIds - account.id else selectedIds + account.id
                }.semantics(mergeDescendants = true) {}.testTag("planning_settings_account_${account.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(account.id in selectedIds, onCheckedChange = null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(account.name, style = MaterialTheme.typography.titleMedium)
                        Text("${p0Money(account.balanceMinor, account.currency)}${if (!available) " · недоступен, снимите выбор" else ""}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (selectedIds.isEmpty()) Text("Выберите хотя бы один счёт или включите автоматический выбор.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Депозиты, кредитные и другие счета не входят в деньги на повседневные расходы. Разные валюты пересчитываются по текущим курсам; итог остаётся оценкой.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Период заканчивается ближайшим основным доходом. Отметьте его в плане поступления; иначе используется конец месяца. Дату можно временно изменить в обзоре.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        localError?.let { P0Message(it) }; PlanningFormStatus(state, viewModel)
        P0Submit("Сохранить настройки ↗", "planning_settings_save", state.busy,
            state.canWrite && (automatic || selectedIds.isNotEmpty() && selectedIds.all { id -> eligible.any { it.id == id } })) {
            runCatching {
                val minor = Money.parseMinor(reserve, SupportedCurrency.fromCode(currency))
                require(minor >= 0) { "Резерв не может быть отрицательным" }
                viewModel.saveSettings(original.copy(version = baseVersion), minor, if (automatic) emptyList() else selectedIds, currency)
            }.onFailure { localError = it.message ?: "Проверьте настройки" }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        state.session?.user?.id?.let { PlanningReminderControl(it) }
    }
    if (choosingCurrency) PlanningCurrencyPicker(currency, "planning_settings_currencies", { choosingCurrency = false }) {
        if (currency != it) reserve = ""
        currency = it; choosingCurrency = false
    }
}

@Composable
internal fun PlanningSchedulesSheet(state: PlanningUiState, onEdit: (String) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val schedules = state.report?.schedules.orEmpty().filter { showArchived || !it.archived }.sortedWith(compareBy<ScheduleDto> { it.archived }.thenBy { it.paused }.thenBy { it.name })
    P0Sheet("planning_schedules", "У всего свой день.", "Планы / регулярные и разовые", state.busy, onDismiss) {
        P0Submit("Новый план ↗", "planning_schedule_add", false, state.canWrite, onNew)
        FilterChip(showArchived, { showArchived = !showArchived }, label = { Text("Показать архив") }, modifier = Modifier.testTag("planning_schedules_archived"))
        if (schedules.isEmpty()) Text("Планов пока нет. Добавьте будущий расход или поступление.", style = MaterialTheme.typography.bodyMedium)
        schedules.forEach { plan ->
            Surface(shape = P0Shape, color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy, role = Role.Button) { onEdit(plan.id) }.testTag("planning_schedule_${plan.id}")) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(plan.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); Text("↗", style = MaterialTheme.typography.titleLarge)
                    }
                    Text((if (plan.kind == "income") "+ " else "− ") + planningMoney(plan.amountMinor, plan.currency), style = MaterialTheme.typography.headlineSmall)
                    Text("${planningRecurrence(plan.frequency)}${if (plan.interval > 1) " · интервал ${plan.interval}" else ""} · с ${planningShortDate(plan.startDate)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (plan.archived || plan.paused || plan.blockedReason != null) Text(if (plan.archived) "В архиве" else if (plan.paused) "На паузе" else planningBlockedReason(plan.blockedReason),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun PlanningCurrencyPicker(selected: String, tag: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    P0OptionsSheet("Валюта", SupportedCurrency.entries.map { P0Option(it.code, "${it.symbol} · ${it.code}", it.title) }, selected, tag, onDismiss, onSelect)
}

@Composable
private fun PlanningSwitch(title: String, description: String, checked: Boolean, enabled: Boolean, tag: String, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChecked, enabled = enabled, modifier = Modifier.semantics { contentDescription = title }.testTag(tag))
    }
}

internal fun planningInstant(date: String, time: String): String = LocalDate.parse(date).atTime(LocalTime.parse(time))
    .atOffset(ZoneOffset.ofHours(5)).toInstant().toString()
