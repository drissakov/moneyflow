package com.moneyflow.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.remote.ImportPreviewRow
import com.moneyflow.app.data.remote.ImportReceiptDto
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImportsScreen(state: ImportUiState, viewModel: ImportViewModel, manualPending: Boolean) {
    var previewConfirmation by rememberSaveable { mutableStateOf(false) }
    var commitConfirmation by rememberSaveable { mutableStateOf(false) }
    var newAccount by rememberSaveable { mutableStateOf(false) }
    var recoveryReviewed by rememberSaveable { mutableStateOf(false) }
    var forgetConfirmation by rememberSaveable { mutableStateOf(false) }
    var undoConfirmation by remember { mutableStateOf<ImportReceiptDto?>(null) }
    var pickerToken by remember { mutableStateOf<String?>(null) }
    var pickerAccount by remember { mutableStateOf<String?>(null) }
    var uncategorizedOnly by rememberSaveable(state.draft?.id) { mutableStateOf(false) }
    var lastCategoryEditedId by rememberSaveable(state.draft?.id) { mutableStateOf<Int?>(null) }
    val possibleMovementIds = remember(state.parsed) {
        state.parsed?.statement?.rows?.filter { it.possibleTransfer }?.map { it.rowId }?.toSet().orEmpty()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pickerToken == state.session?.token && pickerAccount == state.accountId) viewModel.choosePdf(uri)
    }
    val editable = state.session != null && !state.busy && state.pending == null && !manualPending &&
        !state.recoveryUnavailable && !state.recoveryChecking
    LaunchedEffect(state.session?.token) { previewConfirmation = false; commitConfirmation = false; undoConfirmation = null; newAccount = false;
        recoveryReviewed = false; forgetConfirmation = false }
    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxSize().testTag("imports_screen"),
        contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                P0Heading("05.2", "Из выписки — в учёт.", "Текстовые PDF Kaspi и Halyk · счета в KZT")
            }
        }
        if (state.busy || state.historyLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { item { ImportMessage(it, true) } }
        state.notice?.let { item { ImportMessage(it, false) } }
        if (manualPending) item { ImportMessage("Сначала завершите ожидающее изменение в истории операций.", true) }
        if (state.recoveryUnavailable) item {
            ImportCard {
                Text("Подтверждение сохранено, но недоступно", fontWeight = FontWeight.Bold)
                Text("Новые записи заблокированы. Обновите историю: прежний запрос мог выполниться на сервере. Сбой чтения не удаляет сохранённый ключ.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = viewModel::refreshHistory, enabled = !state.historyLoading) { Text("Обновить историю") }
                TextButton(onClick = viewModel::retryRecoveryRead, enabled = !state.busy) { Text("Повторить чтение сохранённого ключа") }
                if (state.historyVerified) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(recoveryReviewed, onCheckedChange = { recoveryReviewed = it })
                        Text("Я просмотрел историю ниже и проверил, какие записи сохранились", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { forgetConfirmation = true }, enabled = recoveryReviewed && !state.busy) {
                        Text("Удалить недоступное локальное подтверждение")
                    }
                }
            }
        }
        state.pending?.let { pending -> item {
            ImportCard {
                Text("Ожидает подтверждения", fontWeight = FontWeight.Bold)
                Text(if (pending.operation == "commit") "Импорт ${pending.selection?.rows?.size} записей мог сохраниться." else "Отмена импорта могла выполниться.")
                Text("Повтор использует исходный ключ и выбор записей. Пока результат неизвестен, новая проверка и изменение выбора заблокированы.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = viewModel::retry, enabled = !state.busy, modifier = Modifier.testTag("import_retry")) { Text("Повторить подтверждение") }
            }
        } }
        item {
            ImportCard {
                Text("1. Выберите PDF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Исходный PDF читается только на устройстве. Сканы и защищённые паролем файлы не поддерживаются.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    pickerToken = state.session?.token; pickerAccount = state.accountId
                    picker.launch(arrayOf("application/pdf"))
                }, enabled = editable, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("import_pick_pdf")) { Text("Выбрать PDF") }
                if (state.parsing) TextButton(onClick = viewModel::cancelParsing) { Text("Отменить разбор") }
            }
        }
        item {
            ImportCard {
                Text("2. Выберите счёт", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                var expanded by rememberSaveable { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, enabled = editable && state.accounts.any { !it.archived },
                        shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("import_account")) {
                        Text(state.account?.let { "${it.name} · ${Money.format(it.balanceMinor, "KZT")}" } ?: "Нет выбранного счёта в KZT",
                            modifier = Modifier.weight(1f))
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Выбрать счёт")
                    }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                        state.accounts.filterNot { it.archived }.forEach { account -> DropdownMenuItem(text = { Text(account.name) },
                            onClick = { expanded = false; viewModel.chooseAccount(account.id) },
                            modifier = Modifier.testTag("import_account_${account.id}")) }
                    }
                }
                TextButton(onClick = { newAccount = true }, enabled = editable, modifier = Modifier.testTag("import_new_account")) { Text("Создать счёт в KZT") }
                if (state.account?.archived == true) Text("Счёт в архиве. Для нового импорта выберите действующий счёт или верните этот из архива. Повтор подтверждения проверяет исходный запрос.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.accountCreationUncertain) {
                    ImportMessage("Счёт мог создаться. Проверьте обновлённый список перед повторной попыткой.", true)
                    TextButton(onClick = viewModel::refreshAccountInventory, enabled = editable) { Text("Обновить счета") }
                    TextButton(onClick = viewModel::acknowledgeAccountInventory, enabled = editable) { Text("Я проверил список; разрешить повтор") }
                }
                state.parsed?.statement?.openingBalanceMinor?.let { opening ->
                    state.account?.let { account ->
                        if (account.balanceMinor != opening) ImportMessage(
                            "Текущий баланс отличается от начала выписки. Импорт прибавит операции к текущему балансу; начальный остаток существующего счёта не изменится.", true)
                    }
                }
                Text("Даты выписки — по времени Казахстана, UTC+5", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.parsed?.statement?.let { statement -> item {
            ImportCard {
                Text("3. Проверьте разбор", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${statement.bank.replaceFirstChar { it.uppercase() }} · ${statement.rows.size} записей · ${statement.periodStart ?: "—"} — ${statement.periodEnd ?: "—"}")
                statement.openingBalanceMinor?.let { Text("Начало: ${Money.format(it, "KZT")}") }
                statement.closingBalanceMinor?.let { Text("Конец: ${Money.format(it, "KZT")}") }
                if (statement.errors.isEmpty()) Text(
                    if (statement.openingBalanceMinor != null && statement.closingBalanceMinor != null)
                        "Сверка разбора завершена. Проверка повторов выполняется на сервере."
                    else "Разбор завершён; автоматическая сверка остатков недоступна. Проверьте записи вручную.",
                    style = MaterialTheme.typography.bodySmall)
                statement.warnings.forEach { ImportMessage(it, false) }
                statement.errors.forEach { ImportMessage(it, true) }
                Button(onClick = { previewConfirmation = true }, enabled = editable && statement.errors.isEmpty() && state.account != null,
                    shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("import_preview")) { Text("Проверить на сервере") }
            }
        } }
        state.draft?.let { draft ->
            val visibleRows = if (!uncategorizedOnly) draft.rows else draft.rows.filter { row ->
                val choice = state.choices[row.rowId]
                choice?.selected == true && !choice.excludeFromAnalytics && (!activeImportCategory(state.categories, choice.categoryId, row.kind) ||
                    (row.rowId == lastCategoryEditedId && state.similarCategoryCount(row.rowId) > 0))
            }
            val visibleIds = visibleRows.map { it.rowId }.toSet()
            val otherMovementReviews = if (!uncategorizedOnly) emptyList() else draft.rows.filter { row ->
                val choice = state.choices[row.rowId]
                row.rowId in possibleMovementIds && choice?.selected == true && !choice.transferReviewed && row.rowId !in visibleIds
            }
            item {
                ImportCard {
                    Text("4. Категории и проверка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Выбрано ${state.selectedCount} из ${draft.rows.size}. Уже импортированные и возможные повторы исключены по умолчанию.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ImportCategoryStat("АВТООПРЕДЕЛЕНО", state.autoCategorizedCount)
                        ImportCategoryStat("БЕЗ КАТЕГОРИИ", state.uncategorizedCount)
                    }
                    Text("Категории уже подобраны там, где удалось найти соответствие. Любую можно исправить. Остальные записи можно импортировать без категории и разобрать позже.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(!uncategorizedOnly, onClick = { uncategorizedOnly = false; lastCategoryEditedId = null }, label = { Text("Все · ${draft.rows.size}") },
                            modifier = Modifier.testTag("import_filter_all"))
                        FilterChip(uncategorizedOnly, onClick = { uncategorizedOnly = true; lastCategoryEditedId = null }, label = { Text("Без категории · ${state.uncategorizedCount}") },
                            modifier = Modifier.testTag("import_filter_uncategorized"))
                    }
                    if (uncategorizedOnly && lastCategoryEditedId?.let { state.similarCategoryCount(it) > 0 } == true)
                        Text("Последняя исправленная запись остаётся здесь, чтобы применить её категорию к похожим.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Одинаковые покупки внутри выписки остаются отдельными записями. Переводы и снятия требуют отдельной проверки.", style = MaterialTheme.typography.bodySmall)
                    Text("Баланс до импорта: ${Money.format(draft.balanceBeforeMinor, "KZT")}")
                    state.projectedBalance?.let { Text("Прогноз выбранного импорта: ${Money.format(it.toString(), "KZT")}") }
                    if (state.needsTransferReview) ImportMessage("Для отмеченных возможных переводов выберите способ учёта ниже.", true)
                    if (state.differsFromStatement) {
                        ImportMessage("Прогноз не совпадает с конечным остатком выписки. Это может быть частичный импорт, исключённые повторы или другой исходный баланс.", true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(state.balanceDifferenceAccepted, onCheckedChange = viewModel::acceptBalanceDifference, enabled = editable,
                                modifier = Modifier.testTag("import_accept_difference"))
                            Text("Я проверил расхождение и выбранные записи", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(onClick = { commitConfirmation = true }, enabled = state.canCommit && !manualPending,
                        shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.fillMaxWidth().testTag("import_commit")) { Text("Импортировать · ${state.selectedCount}") }
                }
            }
            if (otherMovementReviews.isNotEmpty()) {
                item {
                    ImportMessage("Ещё ${otherMovementReviews.size} записей с категориями требуют проверки движения денег. Они показаны ниже, чтобы ничего не пропустить.", true)
                }
                items(otherMovementReviews, key = { "movement_review_${it.rowId}" }) { row ->
                    ImportReviewRow(row, state.choices[row.rowId] ?: ImportRowChoice(false), true, state, viewModel, editable) { lastCategoryEditedId = it }
                }
            }
            if (uncategorizedOnly && visibleRows.isEmpty()) item {
                ImportCard {
                    Text("С категориями разобрались.", style = MaterialTheme.typography.titleLarge)
                    Text("У всех выбранных доходов и расходов есть категория. Проверьте движение денег и повторы перед импортом.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { uncategorizedOnly = false }, modifier = Modifier.testTag("import_show_all")) { Text("Показать все записи") }
                }
            }
            items(visibleRows, key = { "preview_${it.rowId}" }) { row ->
                ImportReviewRow(row, state.choices[row.rowId] ?: ImportRowChoice(false), row.rowId in possibleMovementIds, state, viewModel, editable) { lastCategoryEditedId = it }
            }
        }
        state.receipt?.let { receipt -> item {
            ImportCard {
                Text(if (receipt.status == "undone") "Импорт отменён" else "Импорт сохранён", fontWeight = FontWeight.Bold)
                Text("Записей: ${receipt.importedCount}. Не выбрано: ${receipt.skippedCount}.")
                Text("${if (receipt.status == "undone") "Баланс после этой отмены" else "Баланс после этого импорта"}: ${Money.format(receipt.balanceMinor, "KZT")}")
            }
        } }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("История импортов", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::refreshHistory, enabled = !state.historyLoading, modifier = Modifier.testTag("import_history_refresh")) { Text("Обновить") }
            }
            Text("Последние 20 проверок и импортов. Отмена удаляет только операции, созданные выбранным импортом.", style = MaterialTheme.typography.bodySmall)
        }
        items(state.history, key = { "history_${it.id}" }) { receipt -> ImportCard {
            Text("${receipt.bank.replaceFirstChar { it.uppercase() }} · ${when (receipt.status) { "committed" -> "Сохранён"; "undone" -> "Отменён"; else -> "Черновик" }}", fontWeight = FontWeight.SemiBold)
            val date = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of(state.timezone)).format(Instant.parse(receipt.createdAt))
            Text("${state.accounts.firstOrNull { it.id == receipt.accountId }?.name ?: "Счёт импорта"} · $date",
                style = MaterialTheme.typography.bodySmall)
            Text("Добавлено ${receipt.importedCount}, не выбрано ${receipt.skippedCount}", style = MaterialTheme.typography.bodySmall)
            if (receipt.status == "committed") TextButton(onClick = { undoConfirmation = receipt }, enabled = editable,
                modifier = Modifier.testTag("import_undo_${receipt.id}")) { Text("Отменить импорт") }
        } }
    }
    if (previewConfirmation) AlertDialog(onDismissRequest = { previewConfirmation = false }, title = { Text("Проверить операции на сервере?") },
        text = { Text("Будут переданы разобранные записи и остатки для проверки повторов и подбора категорий. Исходный PDF не передаётся. Эта проверка ещё не добавляет операции на счёт.") },
        confirmButton = { TextButton(onClick = { previewConfirmation = false; viewModel.preview() }, enabled = editable) { Text("Передать на проверку") } },
        dismissButton = { TextButton(onClick = { previewConfirmation = false }) { Text("Отмена") } })
    if (commitConfirmation) AlertDialog(onDismissRequest = { commitConfirmation = false }, title = { Text("Сохранить выбранные операции?") },
        text = { Text("${state.selectedCount} записей изменят баланс выбранного счёта. Проверьте категории, возможные повторы и движение денег. Этот импорт можно отменить в истории.") },
        confirmButton = { TextButton(onClick = { commitConfirmation = false; viewModel.commit() }, enabled = editable && state.canCommit,
            modifier = Modifier.testTag("import_commit_confirm")) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { commitConfirmation = false }) { Text("Отмена") } })
    undoConfirmation?.let { receipt -> AlertDialog(onDismissRequest = { undoConfirmation = null }, title = { Text("Отменить этот импорт?") },
        text = { Text("Будут удалены оставшиеся операции из ${receipt.importedCount} записей, созданных этим импортом. Другие записи сохранятся; баланс и аналитика обновятся.") },
        confirmButton = { TextButton(onClick = { undoConfirmation = null; viewModel.undo(receipt) }, enabled = editable,
            modifier = Modifier.testTag("import_undo_confirm")) { Text("Удалить записи импорта") } },
        dismissButton = { TextButton(onClick = { undoConfirmation = null }) { Text("Отмена") } }) }
    if (newAccount) ImportNewAccountDialog(state, viewModel) { newAccount = false }
    if (forgetConfirmation) AlertDialog(onDismissRequest = { forgetConfirmation = false },
        title = { Text("Удалить локальное подтверждение?") },
        text = { Text("Серверные операции останутся. После удаления восстановить прежний ключ нельзя. Создавайте новый импорт только после проверки истории и уже сохранённых записей.") },
        confirmButton = { TextButton(onClick = { forgetConfirmation = false; viewModel.forgetUnavailableRecovery(recoveryReviewed) }) { Text("Удалить после проверки") } },
        dismissButton = { TextButton(onClick = { forgetConfirmation = false }) { Text("Отмена") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportReviewRow(row: ImportPreviewRow, choice: ImportRowChoice, transfer: Boolean,
    state: ImportUiState, viewModel: ImportViewModel, editable: Boolean, onCategoryEdited: (Int) -> Unit) {
    var details by rememberSaveable(row.rowId) { mutableStateOf(false) }
    val hasActiveCategory = activeImportCategory(state.categories, choice.categoryId, row.kind)
    ImportCard {
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(choice.selected, onCheckedChange = { viewModel.selectRow(row.rowId, it) },
                enabled = editable && row.duplicate != "exact" && (row.duplicate != "possible" || choice.allowPossibleDuplicate),
                modifier = Modifier.testTag("import_row_${row.rowId}"))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.of(state.timezone)).format(Instant.parse(row.occurredAt))
                Text(row.note.ifBlank { "Без описания" }, style = MaterialTheme.typography.titleMedium,
                    maxLines = if (details) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                Text("$date · ${if (row.kind == "income") "+" else "−"}${Money.format(row.amountMinor, "KZT")}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (row.duplicate != "new") Text(if (row.duplicate == "exact") "Уже импортировано из этого PDF" else "Возможный повтор: совпадений ${row.matchingCount}",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        if (row.duplicate == "possible") Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(choice.allowPossibleDuplicate, onCheckedChange = { viewModel.allowDuplicate(row.rowId, it) }, enabled = editable)
            Text("Это отдельная операция; включить несмотря на совпадение", style = MaterialTheme.typography.bodySmall)
        }
        if (choice.selected) {
            if (transfer) {
                Text(if (choice.transferReviewed) "Способ учёта проверен вами. Его можно изменить." else
                    "Похоже на перевод или движение наличных. Выберите способ учёта: подсказка не подтверждает перевод автоматически.",
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(choice.transferReviewed && choice.excludeFromAnalytics, onClick = { viewModel.movement(row.rowId, true) },
                        label = { Text("Движение денег") }, enabled = editable,
                        modifier = Modifier.testTag("import_as_movement_${row.rowId}"))
                    FilterChip(choice.transferReviewed && !choice.excludeFromAnalytics, onClick = { viewModel.movement(row.rowId, false) },
                        label = { Text("Доход / расход") }, enabled = editable,
                        modifier = Modifier.testTag("import_as_transaction_${row.rowId}"))
                }
            }
            if (transfer || details || choice.excludeFromAnalytics) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(choice.excludeFromAnalytics, onCheckedChange = { viewModel.movement(row.rowId, it) }, enabled = editable,
                    modifier = Modifier.testTag("import_movement_${row.rowId}"))
                Text("Движение денег — вне доходов и расходов", style = MaterialTheme.typography.bodySmall)
            }
            if (choice.excludeFromAnalytics) Text("Запись изменит баланс, но не войдёт в доходы и расходы отчётов.", style = MaterialTheme.typography.labelSmall)
            if (!choice.excludeFromAnalytics) {
                val label = if (!hasActiveCategory) "Выберите категорию или оставьте на потом" else if (choice.categoryEdited) "Выбрано вами" else when (row.categorySuggestionSource) {
                    "history" -> "По вашим операциям"
                    "rule" -> "Определено автоматически"
                    else -> "Категория"
                }
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("import_category_origin_${row.rowId}"))
                CategorySelector(state.categories.filter { !it.archived || it.id == choice.categoryId }, row.kind, choice.categoryId,
                    { viewModel.category(row.rowId, it); onCategoryEdited(row.rowId) }, enabled = editable, tagPrefix = "import_category_${row.rowId}")
                val similar = if (choice.categoryEdited && hasActiveCategory) state.similarCategoryCount(row.rowId) else 0
                if (choice.categoryEdited && hasActiveCategory && similar > 0) {
                    OutlinedButton(onClick = { viewModel.applyCategoryToSimilar(row.rowId) }, enabled = editable,
                        shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("import_apply_similar_${row.rowId}")) {
                        Text("Применить к похожим ($similar)")
                    }
                    Text("Только выбранные операции. Другие ручные исправления сохранятся.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { details = !details }, modifier = Modifier.testTag("import_details_${row.rowId}")) {
                Text(if (details) "Свернуть детали ↑" else "Параметры операции ↗")
            }
        }
    }
}

@Composable
private fun ImportCategoryStat(label: String, count: Int) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImportNewAccountDialog(state: ImportUiState, viewModel: ImportViewModel, dismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var opening by rememberSaveable { mutableStateOf(BigDecimal.valueOf(state.parsed?.statement?.openingBalanceMinor ?: 0, 2).toPlainString()) }
    var accepted by rememberSaveable { mutableStateOf(false) }
    val initialAccount = remember { state.accountId }
    LaunchedEffect(state.accountId) { if (state.accountId != initialAccount) dismiss() }
    AlertDialog(onDismissRequest = { if (!state.busy) dismiss() }, title = { Text("Новый счёт для выписки") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Название счёта") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("import_account_name"))
            OutlinedTextField(opening, { opening = it; accepted = false }, label = { Text("Остаток перед периодом, KZT") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("import_account_opening"))
            Text("Предложен начальный остаток выписки, если он распознан. Конечный остаток не используется как начальный.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(accepted, onCheckedChange = { accepted = it }, modifier = Modifier.testTag("import_accept_opening"))
                Text("Я проверил и принимаю этот начальный остаток", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { ImportMessage(it, true) }
        } }, confirmButton = { Button(onClick = { viewModel.createAccount(name, opening, accepted) }, enabled = !state.busy && accepted && !state.accountCreationUncertain,
            modifier = Modifier.testTag("import_create_account")) { Text("Создать") } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.busy) { Text("Отмена") } })
}

@Composable private fun ImportCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = P0Shape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun ImportMessage(text: String, error: Boolean) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(1.dp, if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(if (error) Icons.Default.Warning else Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}
