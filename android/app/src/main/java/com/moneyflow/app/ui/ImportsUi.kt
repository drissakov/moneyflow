package com.moneyflow.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.remote.ImportPreviewRow
import com.moneyflow.app.data.remote.ImportReceiptDto
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pickerToken == state.session?.token && pickerAccount == state.accountId) viewModel.choosePdf(uri)
    }
    val editable = state.session != null && !state.busy && state.pending == null && !manualPending &&
        !state.recoveryUnavailable && !state.recoveryChecking
    LaunchedEffect(state.session?.token) { previewConfirmation = false; commitConfirmation = false; undoConfirmation = null; newAccount = false;
        recoveryReviewed = false; forgetConfirmation = false }
    LazyColumn(Modifier.fillMaxSize().widthIn(max = 680.dp).testTag("imports_screen"),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Импорт выписки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Текстовые PDF Kaspi и Halyk · счета в KZT", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.busy || state.historyLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { item { ImportMessage(it, true) } }
        state.notice?.let { item { ImportMessage(it, false) } }
        if (manualPending) item { ImportMessage("Сначала завершите ожидающую обычную операцию в обзоре.", true) }
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
                Text("1. Выберите PDF", fontWeight = FontWeight.Bold)
                Text("Исходный PDF читается только на устройстве. Сканы и защищённые паролем файлы не поддерживаются.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    pickerToken = state.session?.token; pickerAccount = state.accountId
                    picker.launch(arrayOf("application/pdf"))
                }, enabled = editable, modifier = Modifier.testTag("import_pick_pdf")) { Text("Выбрать PDF") }
                if (state.parsing) TextButton(onClick = viewModel::cancelParsing) { Text("Отменить разбор") }
            }
        }
        item {
            ImportCard {
                Text("2. Выберите счёт", fontWeight = FontWeight.Bold)
                var expanded by rememberSaveable { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, enabled = editable && state.accounts.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("import_account")) {
                        Text(state.account?.let { "${it.name} · ${Money.format(it.balanceMinor, "KZT")}" } ?: "Нет выбранного счёта в KZT")
                    }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                        state.accounts.forEach { account -> DropdownMenuItem(text = { Text(account.name) },
                            onClick = { expanded = false; viewModel.chooseAccount(account.id) },
                            modifier = Modifier.testTag("import_account_${account.id}")) }
                    }
                }
                TextButton(onClick = { newAccount = true }, enabled = editable, modifier = Modifier.testTag("import_new_account")) { Text("Создать счёт в KZT") }
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
                Text("Часовой пояс дат выписки", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Asia/Qyzylorda" to "Кызылорда", "Asia/Almaty" to "Алматы").forEach { (zone, title) ->
                        FilterChip(state.timezone == zone, onClick = { viewModel.setTimezone(zone) }, label = { Text(title) }, enabled = editable)
                    }
                }
            }
        }
        state.parsed?.statement?.let { statement -> item {
            ImportCard {
                Text("3. Проверьте разбор", fontWeight = FontWeight.Bold)
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
                    modifier = Modifier.testTag("import_preview")) { Text("Проверить на сервере") }
            }
        } }
        state.draft?.let { draft ->
            item {
                ImportCard {
                    Text("4. Выберите записи", fontWeight = FontWeight.Bold)
                    Text("Выбрано ${state.selectedCount} из ${draft.rows.size}. Уже импортированные и возможные повторы исключены по умолчанию.")
                    Text("Одинаковые покупки внутри выписки остаются отдельными записями. Переводы и снятия проверьте вручную.", style = MaterialTheme.typography.bodySmall)
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
                        modifier = Modifier.testTag("import_commit")) { Text("Импортировать выбранные") }
                }
            }
            items(draft.rows, key = { "preview_${it.rowId}" }) { row ->
                val possibleTransfer = state.parsed?.statement?.rows?.firstOrNull { it.rowId == row.rowId }?.possibleTransfer == true
                ImportReviewRow(row, state.choices[row.rowId] ?: ImportRowChoice(false), possibleTransfer, state, viewModel, editable)
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
            Text("${state.accounts.firstOrNull { it.id == receipt.accountId }?.name ?: "Счёт импорта"} · ${receipt.createdAt}",
                style = MaterialTheme.typography.bodySmall)
            Text("Добавлено ${receipt.importedCount}, не выбрано ${receipt.skippedCount}", style = MaterialTheme.typography.bodySmall)
            if (receipt.status == "committed") TextButton(onClick = { undoConfirmation = receipt }, enabled = editable,
                modifier = Modifier.testTag("import_undo_${receipt.id}")) { Text("Отменить импорт") }
        } }
    }
    if (previewConfirmation) AlertDialog(onDismissRequest = { previewConfirmation = false }, title = { Text("Проверить операции на сервере?") },
        text = { Text("Будут переданы разобранные записи и остатки для проверки повторов. Исходный PDF не передаётся. Эта проверка ещё не добавляет операции на счёт.") },
        confirmButton = { TextButton(onClick = { previewConfirmation = false; viewModel.preview() }) { Text("Передать на проверку") } },
        dismissButton = { TextButton(onClick = { previewConfirmation = false }) { Text("Отмена") } })
    if (commitConfirmation) AlertDialog(onDismissRequest = { commitConfirmation = false }, title = { Text("Сохранить выбранные операции?") },
        text = { Text("${state.selectedCount} записей изменят баланс выбранного счёта. Проверьте категории, возможные повторы и движение денег. Этот импорт можно отменить в истории.") },
        confirmButton = { TextButton(onClick = { commitConfirmation = false; viewModel.commit() }, modifier = Modifier.testTag("import_commit_confirm")) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { commitConfirmation = false }) { Text("Отмена") } })
    undoConfirmation?.let { receipt -> AlertDialog(onDismissRequest = { undoConfirmation = null }, title = { Text("Отменить этот импорт?") },
        text = { Text("Будут удалены оставшиеся операции из ${receipt.importedCount} записей, созданных этим импортом. Другие записи сохранятся; баланс и аналитика обновятся.") },
        confirmButton = { TextButton(onClick = { undoConfirmation = null; viewModel.undo(receipt) }, modifier = Modifier.testTag("import_undo_confirm")) { Text("Удалить записи импорта") } },
        dismissButton = { TextButton(onClick = { undoConfirmation = null }) { Text("Отмена") } }) }
    if (newAccount) ImportNewAccountDialog(state, viewModel) { newAccount = false }
    if (forgetConfirmation) AlertDialog(onDismissRequest = { forgetConfirmation = false },
        title = { Text("Удалить локальное подтверждение?") },
        text = { Text("Серверные операции останутся. После удаления восстановить прежний ключ нельзя. Создавайте новый импорт только после проверки истории и уже сохранённых записей.") },
        confirmButton = { TextButton(onClick = { forgetConfirmation = false; viewModel.forgetUnavailableRecovery(recoveryReviewed) }) { Text("Удалить после проверки") } },
        dismissButton = { TextButton(onClick = { forgetConfirmation = false }) { Text("Отмена") } })
}

@Composable
private fun ImportReviewRow(row: ImportPreviewRow, choice: ImportRowChoice, transfer: Boolean,
    state: ImportUiState, viewModel: ImportViewModel, editable: Boolean) {
    ImportCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(choice.selected, onCheckedChange = { viewModel.selectRow(row.rowId, it) },
                enabled = editable && row.duplicate != "exact" && (row.duplicate != "possible" || choice.allowPossibleDuplicate),
                modifier = Modifier.testTag("import_row_${row.rowId}"))
            Column(Modifier.weight(1f)) {
                val date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.of(state.timezone)).format(Instant.parse(row.occurredAt))
                Text("$date · ${if (row.kind == "income") "+" else "−"}${Money.format(row.amountMinor, "KZT")}", fontWeight = FontWeight.SemiBold)
                Text(row.note.ifBlank { "Без описания" }, style = MaterialTheme.typography.bodySmall)
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
                Text("Похоже на перевод или движение наличных. Это подсказка, проверьте назначение сами.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { viewModel.movement(row.rowId, true) }, enabled = editable) { Text("Движение денег") }
                    TextButton(onClick = { viewModel.movement(row.rowId, false) }, enabled = editable) { Text("Доход / расход") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(choice.excludeFromAnalytics, onCheckedChange = { viewModel.movement(row.rowId, it) }, enabled = editable,
                    modifier = Modifier.testTag("import_movement_${row.rowId}"))
                Text("Перевод между своими счетами / движение денег — исключить из аналитики", style = MaterialTheme.typography.bodySmall)
            }
            if (choice.excludeFromAnalytics) Text("Запись изменит баланс, но не войдёт в доходы и расходы отчётов.", style = MaterialTheme.typography.labelSmall)
            CategorySelector(state.categories, row.kind, choice.categoryId, { viewModel.category(row.rowId, it) },
                enabled = editable, tagPrefix = "import_category_${row.rowId}")
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
            OutlinedTextField(name, { name = it }, label = { Text("Название счёта") }, singleLine = true, modifier = Modifier.testTag("import_account_name"))
            OutlinedTextField(opening, { opening = it; accepted = false }, label = { Text("Остаток перед периодом, KZT") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.testTag("import_account_opening"))
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
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable private fun ImportMessage(text: String, error: Boolean) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp)) { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) }
}
