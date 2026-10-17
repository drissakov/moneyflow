package com.moneyflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.MoneyTransaction
import com.moneyflow.app.data.domain.MoneyTransfer
import com.moneyflow.app.data.domain.SupportedCurrency
import com.moneyflow.app.data.remote.LedgerFilter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LedgerScreen(state: LedgerUiState, viewModel: LedgerViewModel,
    initialAction: String? = null, onActionConsumed: () -> Unit = {}) {
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var transferId by rememberSaveable { mutableStateOf<String?>(null) }
    var transfer by remember { mutableStateOf<MoneyTransfer?>(null) }
    var query by rememberSaveable { mutableStateOf(state.filter.query) }
    val currentFilter by rememberUpdatedState(state.filter)
    val initialRevision = rememberSaveable(editor, selectedId, transferId) { state.revision }
    val activeAccounts = state.accounts.filterNot { it.archived }
    P0ResetOnUserChange(state.session?.user?.id) { editor = null; selectedId = null; transfer = null; transferId = null; query = state.filter.query }
    LaunchedEffect(state.revision) {
        if (state.revision != initialRevision && editor != "filters") { editor = null; selectedId = null; transfer = null; transferId = null }
    }
    LaunchedEffect(transferId, editor, state.busy) {
        val id = transferId
        if (editor == "transfer" && id != null && transfer?.id != id && !state.busy && state.error == null)
            viewModel.fetchTransfer(id) { transfer = it }
    }
    LaunchedEffect(initialAction) {
        if (initialAction != null) {
            viewModel.clearMessage(); selectedId = null; transferId = null; transfer = null
            editor = initialAction; onActionConsumed()
        }
    }
    LaunchedEffect(query) {
        delay(350)
        if (currentFilter.query != query) viewModel.setFilter(currentFilter.copy(query = query.take(100)))
    }

    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("ledger_screen"),
        contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "heading") {
            P0Heading("02", if (state.filter.analyticsOnly) "За цифрами —\nоперации." else "История\nв деталях.",
                if (state.filter.analyticsOnly) "Операции выбранного отчёта. Даты — по времени Казахстана, UTC+5."
                else "Все счета. Каждая операция. В одном месте.")
        }
        if (state.filter.analyticsOnly && state.filter.currency == null) item(key = "original_currencies") {
            Text("Здесь показаны операции всех валют с исходными суммами. В общем отчёте они пересчитаны в тенге по указанным там курсам.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("ledger_scope_all_currencies"))
        }
        item(key = "actions") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { viewModel.clearMessage(); selectedId = null; editor = "create" }, enabled = state.canWrite && activeAccounts.isNotEmpty(),
                    shape = P0Shape, contentPadding = PaddingValues(16.dp), modifier = Modifier.testTag("ledger_add")) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text(" Операция")
                }
                OutlinedButton(onClick = { viewModel.clearMessage(); transfer = null; transferId = null; editor = "transfer" },
                    enabled = state.canWrite && activeAccounts.size >= 2, shape = P0Shape,
                    contentPadding = PaddingValues(16.dp), modifier = Modifier.testTag("ledger_transfer")) { Text("↔  Перевод") }
            }
        }
        item(key = "search") {
            OutlinedTextField(query, { query = it.take(100) }, singleLine = true, placeholder = { Text("Поиск по описанию") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, shape = P0Shape,
                trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить поиск") } } } else null,
                modifier = Modifier.fillMaxWidth().testTag("ledger_search"))
        }
        item(key = "filters") {
            val count = listOf(state.filter.accountId, state.filter.categoryId, state.filter.kind, state.filter.from, state.filter.to,
                state.filter.currency, if (state.filter.analyticsOnly) "analytics" else null,
                if (state.filter.uncategorized) "uncategorized" else null, if (state.filter.categoryExact) "exact" else null,
                if (state.filter.showDeleted) "deleted" else null).count { it != null }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(count > 0, onClick = { editor = "filters" }, label = { Text(if (count > 0) "Фильтры · $count" else "Фильтры ↗") },
                    modifier = Modifier.testTag("ledger_filters"))
                if (count > 0) TextButton(onClick = { viewModel.setFilter(LedgerFilter(query = query)) }, modifier = Modifier.testTag("ledger_filters_reset")) { Text("Сбросить") }
                TextButton(onClick = viewModel::refresh, enabled = !state.loading && !state.busy, modifier = Modifier.testTag("ledger_refresh")) { Text("Обновить") }
            }
            state.accounts.firstOrNull { it.id == state.filter.accountId }?.let {
                Text("Счёт: ${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.filter.currency?.let { Text("Валюта: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("ledger_scope_currency")) }
            if (state.filter.uncategorized || state.filter.categoryId != null) {
                Text(if (state.filter.uncategorized) "Без категории" else
                    categoryPath(state.filter.categoryId, state.categories) + if (state.filter.categoryExact) " · без подкатегорий" else " · включая подкатегории",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("ledger_scope_category"))
            }
            if (state.filter.analyticsOnly) {
                val start = state.filter.from?.let { ledgerFilterDate(it, true) }
                val end = state.filter.to?.let { LocalDate.parse(ledgerFilterDate(it, true)).minusDays(1).toString() }
                Text(listOfNotNull(start, end).joinToString(" — ") + " · Только включённые в отчёты",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("ledger_scope_report"))
            }
        }
        if (state.pending != null || state.manualPending || state.recoveryUnavailable) item(key = "recovery") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                P0Message(if (state.recoveryUnavailable) "Не удалось проверить сохранённое действие. Обновите данные, прежде чем вносить изменения."
                    else "Результат последнего действия пока не подтверждён. Повторная проверка безопасна: дубль не создастся.")
                OutlinedButton(onClick = viewModel::retryPending, enabled = !state.busy && !state.externalBlocked && !state.recoveryChecking && !state.recoveryUnavailable,
                    modifier = Modifier.testTag("ledger_retry_pending")) { Text("Проверить действие") }
            }
        }
        if (state.externalBlocked) item(key = "blocked") { P0Message("Сначала завершите или проверьте неподтверждённую операцию во вкладке импорта или на главной.") }
        state.notice?.let { notice -> item(key = "notice") { P0Message(notice, viewModel::clearMessage) } }
        state.error?.let { error -> item(key = "error") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                P0Message(error, viewModel::clearMessage)
                TextButton(onClick = viewModel::refresh, enabled = !state.loading && !state.busy) { Text("Повторить загрузку") }
            }
        } }
        if (state.loading || state.busy) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.transactions.isEmpty() && !state.loading) item(key = "empty") {
            Surface(shape = P0Shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Пока чистый лист.", style = MaterialTheme.typography.headlineSmall)
                    Text(if (query.isNotBlank() || state.filter != LedgerFilter()) "По этим условиям операций нет. Измените фильтры или поиск."
                        else "Добавьте первую операцию или импортируйте выписку.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val grouped = state.transactions.groupBy { ledgerFilterDate(it.occurredAt, state.filter.analyticsOnly) }
        grouped.forEach { (date, transactions) ->
            item(key = "date_$date") {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ledgerDay(date, state.filter.analyticsOnly), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(transactions.size.toString().padStart(2, '0'), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(transactions, key = { it.id }) { transaction ->
                LedgerRow(transaction, state, enabled = !state.busy) {
                    viewModel.clearMessage()
                    if (transaction.transferId != null) viewModel.fetchTransfer(transaction.transferId) { loaded -> transfer = loaded; transferId = loaded.id; editor = "transfer" }
                    else { selectedId = transaction.id; editor = "edit" }
                }
            }
        }
        if (state.nextCursor != null) item(key = "more") {
            OutlinedButton(onClick = viewModel::loadMore, enabled = !state.loadingMore && !state.loading,
                shape = P0Shape, contentPadding = PaddingValues(18.dp), modifier = Modifier.fillMaxWidth().testTag("ledger_load_more")) {
                if (state.loadingMore) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Ещё операции ↓")
            }
        }
        if (state.transactions.isNotEmpty() && state.nextCursor == null && !state.loading) item(key = "end") {
            Text(if (state.offline) "Конец сохранённой части истории" else "Вся история по выбранным условиям", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
    }
    when (editor) {
        "create" -> P0TransactionEditor(state, viewModel, null, state.filter.accountId) { editor = null }
        "edit" -> state.transactions.firstOrNull { it.id == selectedId }?.let { tx -> P0TransactionEditor(state, viewModel, tx) { editor = null; selectedId = null } }
        "transfer" -> if (transferId == null || transfer?.id == transferId)
            P0TransferEditor(state, viewModel, transfer) { editor = null; transfer = null; transferId = null }
        else P0Sheet("ledger_transfer_loading", "Открываем перевод.", "История / перевод", state.busy,
            { editor = null; transferId = null }) {
            if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
            state.error?.let { P0Message(it) }
            OutlinedButton(onClick = { transferId?.let { id -> viewModel.fetchTransfer(id) { transfer = it } } }, enabled = !state.busy) { Text("Повторить загрузку") }
        }
        "filters" -> P0LedgerFilters(state, viewModel) { editor = null }
    }
}

@Composable
private fun LedgerRow(transaction: MoneyTransaction, state: LedgerUiState, enabled: Boolean, onClick: () -> Unit) {
    val account = state.accounts.firstOrNull { it.id == transaction.accountId }
    val deleted = transaction.deletedAt != null
    val movement = transaction.transferId != null
    val title = if (movement) when (transaction.transferRole) {
        "fee" -> "Комиссия перевода"
        "from" -> "Перевод · списание"
        else -> "Перевод · зачисление"
    } else transaction.note.ifBlank { if (transaction.kind == "income") "Доход" else "Расход" }
    Surface(shape = P0Shape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(enabled, onClick = onClick).testTag("ledger_row_${transaction.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (transaction.kind == "expense") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = if (transaction.kind == "expense") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                    Text(if (movement) "↔" else if (transaction.kind == "income") "↙" else "↗", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (deleted) TextDecoration.LineThrough else null)
                    Text("${account?.name ?: "Счёт"} · ${ledgerFilterTime(transaction.occurredAt, state.filter.analyticsOnly)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (movement && transaction.note.isNotBlank()) Text(transaction.note, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("↗", style = MaterialTheme.typography.titleLarge)
            }
            Text("${if (transaction.kind == "income") "+" else "−"}${p0Money(transaction.amountMinor, transaction.currency)}",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium,
                textDecoration = if (deleted) TextDecoration.LineThrough else null)
            Text(listOfNotNull(if (deleted) "Удалено" else null,
                if (movement && transaction.transferRole != "fee") "Между своими счетами" else categoryPath(transaction.categoryId, state.categories),
                if (transaction.source == "import") "Из выписки" else null,
                if (transaction.excludeFromAnalytics && !movement) "Вне отчётов" else null).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun ledgerDay(iso: String, reportScope: Boolean): String {
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    val today = LocalDate.now(ledgerFilterZone(reportScope))
    return when (date) { today -> "Сегодня"; today.minusDays(1) -> "Вчера"
        else -> date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun P0LedgerFilters(state: LedgerUiState, viewModel: LedgerViewModel, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf(state.filter) }
    var picker by remember { mutableStateOf<String?>(null) }
    var fromDate by remember { mutableStateOf(filter.from?.let { ledgerFilterDate(it, filter.analyticsOnly) }.orEmpty()) }
    var toDate by remember { mutableStateOf(filter.to?.let { LocalDate.parse(ledgerFilterDate(it, filter.analyticsOnly)).minusDays(1).toString() }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    P0Sheet("ledger_filter_sheet", "Найти нужное.", "История / фильтры", false, onDismiss) {
        P0Choice("Счёт", state.accounts.firstOrNull { it.id == filter.accountId }?.name ?: "Все счета", "ledger_filter_account") { picker = "account" }
        P0Choice("Валюта операций", filter.currency ?: "Все валюты", "ledger_filter_currency") { picker = "currency" }
        Text("Тип операции", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "Все", "expense" to "Расход", "income" to "Доход").forEach { (kind, name) ->
                FilterChip(filter.kind == kind, { filter = filter.copy(kind = kind, categoryId = null, uncategorized = false, categoryExact = false) }, label = { Text(name) }, modifier = Modifier.testTag("ledger_filter_kind_${kind ?: "all"}"))
            }
        }
        P0Choice("Категория", if (filter.uncategorized) "Без категории" else state.categories.firstOrNull { it.id == filter.categoryId }?.let { categoryPath(it.id, state.categories) } ?: "Все категории", "ledger_filter_category") { picker = "category" }
        if (filter.categoryId != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(filter.categoryExact, { filter = filter.copy(categoryExact = it) }, modifier = Modifier.testTag("ledger_filter_exact"))
            Text("Только эта категория, без подкатегорий", Modifier.weight(1f))
        }
        OutlinedTextField(fromDate, { fromDate = it }, label = { Text("С даты · ГГГГ-ММ-ДД") }, singleLine = true,
            shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("ledger_filter_from"))
        OutlinedTextField(toDate, { toDate = it }, label = { Text("По дату включительно · ГГГГ-ММ-ДД") }, singleLine = true,
            shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("ledger_filter_to"))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(filter.showDeleted, { filter = filter.copy(showDeleted = it) }, enabled = !filter.analyticsOnly, modifier = Modifier.testTag("ledger_filter_deleted"))
            Text("Показывать удалённые", Modifier.weight(1f))
        }
        Text("Удалённые операции можно восстановить. Переводы восстанавливаются целиком.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(filter.analyticsOnly, { checked ->
                filter = filter.copy(analyticsOnly = checked, showDeleted = if (checked) false else filter.showDeleted)
            }, modifier = Modifier.testTag("ledger_filter_analytics"))
            Text("Только включённые в отчёты", Modifier.weight(1f))
        }
        if (filter.analyticsOnly) Text("Без удалённых и исключённых операций и основных сумм собственных переводов. Комиссии учитываются как расходы. Даты: UTC+5.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { P0Message(it) }
        P0Submit("Показать операции", "ledger_filter_apply", false) {
            runCatching {
                val start = fromDate.trim().takeIf(String::isNotBlank)?.let { ledgerFilterBoundary(it, filter.analyticsOnly, false) }
                val end = toDate.trim().takeIf(String::isNotBlank)?.let { ledgerFilterBoundary(it, filter.analyticsOnly, true) }
                require(start == null || end == null || java.time.Instant.parse(start).isBefore(java.time.Instant.parse(end))) { "Конец периода должен быть не раньше начала" }
                viewModel.setFilter(filter.copy(from = start, to = end)); onDismiss()
            }.onFailure { error = it.message ?: "Проверьте даты" }
        }
    }
    when (picker) {
        "account" -> P0OptionsSheet("Счёт", listOf(P0Option("", "Все счета")) + state.accounts.map {
            P0Option(it.id, it.name, "${it.currency}${if (it.archived) " · архив" else ""}") }, filter.accountId ?: "", "ledger_filter_accounts",
            { picker = null }) { id ->
                val account = state.accounts.firstOrNull { it.id == id }
                filter = filter.copy(accountId = id.ifBlank { null }, currency = if (filter.currency != null && account != null) account.currency else filter.currency)
                picker = null
            }
        "currency" -> P0OptionsSheet("Валюта операций", listOf(P0Option("", "Все валюты")) + SupportedCurrency.entries.map {
            P0Option(it.code, it.code) }, filter.currency ?: "", "ledger_filter_currencies", { picker = null }) { code ->
                val currency = code.ifBlank { null }
                val account = state.accounts.firstOrNull { it.id == filter.accountId }
                filter = filter.copy(currency = currency, accountId = if (currency != null && account?.currency != currency) null else filter.accountId)
                picker = null
            }
        "category" -> P0OptionsSheet("Категория", listOf(P0Option("", "Все категории"), P0Option("uncategorized", "Без категории")) + state.categories.filter { filter.kind == null || it.kind == filter.kind }.map {
            P0Option(it.id, categoryPath(it.id, state.categories), if (it.kind == "income") "Доход" else "Расход") }, if (filter.uncategorized) "uncategorized" else filter.categoryId ?: "", "ledger_filter_categories",
            { picker = null }) { id ->
                filter = filter.copy(categoryId = id.takeUnless { it.isBlank() || it == "uncategorized" }, uncategorized = id == "uncategorized", categoryExact = false)
                picker = null
            }
    }
}
