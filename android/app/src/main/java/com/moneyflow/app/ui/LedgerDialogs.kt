package com.moneyflow.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.MoneyTransaction
import com.moneyflow.app.data.domain.MoneyTransfer
import com.moneyflow.app.data.domain.SupportedCurrency
import java.time.Instant

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun P0TransactionEditor(state: LedgerUiState, viewModel: LedgerViewModel, original: MoneyTransaction?,
    initialAccountId: String? = null, onDismiss: () -> Unit) {
    val stamp = remember { original?.occurredAt ?: Instant.now().toString() }
    var accountId by rememberSaveable(original?.id) { mutableStateOf(original?.accountId ?: initialAccountId ?: state.accounts.firstOrNull { !it.archived }?.id.orEmpty()) }
    var kind by rememberSaveable(original?.id) { mutableStateOf(original?.kind ?: "expense") }
    var amount by rememberSaveable(original?.id) { mutableStateOf(original?.let { p0Input(it.amountMinor, it.currency) }.orEmpty()) }
    var note by rememberSaveable(original?.id) { mutableStateOf(original?.note.orEmpty()) }
    var categoryId by rememberSaveable(original?.id) { mutableStateOf(original?.categoryId) }
    var excluded by rememberSaveable(original?.id) { mutableStateOf(original?.excludeFromAnalytics ?: false) }
    var date by rememberSaveable(original?.id) { mutableStateOf(p0LocalDate(stamp)) }
    var time by rememberSaveable(original?.id) { mutableStateOf(p0LocalTime(stamp)) }
    var localError by remember { mutableStateOf<String?>(null) }
    var choosingAccount by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val revision = remember { state.revision }
    LaunchedEffect(state.revision) { if (state.revision != revision) onDismiss() }
    val account = state.accounts.firstOrNull { it.id == accountId }
    val deleted = original?.deletedAt != null
    val editable = state.canWrite && !deleted
    if (confirm != null && original != null) {
        val restoring = confirm == "restore"
        P0Confirmation(if (restoring) "Вернуть операцию?" else "Удалить операцию?",
            if (restoring) "Сумма снова повлияет на остаток счёта и отчёты."
            else "Остаток счёта и отчёты будут пересчитаны. Операцию можно вернуть: включите «Показывать удалённые» в фильтрах истории.",
            if (restoring) "Восстановить" else "Удалить операцию", "ledger_transaction_${if (restoring) "restore" else "delete"}",
            state, viewModel, { confirm = null }) {
            if (restoring) viewModel.restoreTransaction(original) else viewModel.deleteTransaction(original)
        }
        return
    }
    P0Sheet("ledger_transaction_editor", if (original == null) "Новая операция." else if (deleted) "Удалённая операция." else "Каждая деталь.",
        if (original == null) "История / добавить" else "История / изменить", state.busy, onDismiss) {
        if (deleted) P0Message("Эта операция удалена и не влияет на деньги. Восстановите её, чтобы продолжить редактирование.")
        if (account?.archived == true) P0Message("Счёт «${account.name}» в архиве. Для восстановления операции сначала верните счёт из архива во вкладке «Счета».")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("expense" to "↗  Расход", "income" to "↙  Доход").forEach { (value, label) ->
                FilterChip(kind == value, onClick = { kind = value; categoryId = null }, label = { Text(label) },
                    enabled = editable, modifier = Modifier.testTag("ledger_transaction_kind_$value"))
            }
        }
        P0Choice("Счёт", account?.let { "${it.name} · ${it.currency}" } ?: "Выберите счёт", "ledger_transaction_account",
            editable && original?.source != "import") { choosingAccount = true }
        if (original?.source == "import") Text("Счёт закреплён за выпиской. Остальные данные можно изменить.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(amount, { amount = it }, label = { Text("Сумма · ${account?.currency.orEmpty()}") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium, shape = P0Shape, enabled = editable,
            modifier = Modifier.fillMaxWidth().testTag("ledger_transaction_amount"))
        CategorySelector(state.categories.filter { !it.archived || it.id == categoryId }, kind, categoryId, { categoryId = it },
            enabled = editable, tagPrefix = "ledger_transaction_category")
        P0DateTimeFields(date, time, { date = it }, { time = it }, editable, "ledger_transaction")
        OutlinedTextField(note, { note = it.take(500) }, label = { Text("Описание") }, minLines = 2, maxLines = 5,
            enabled = editable, shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("ledger_transaction_note"))
        if (original != null && !deleted) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(excluded, { excluded = it }, enabled = editable, modifier = Modifier.testTag("ledger_transaction_exclude"))
                Column(Modifier.weight(1f)) {
                    Text("Не учитывать в отчётах")
                    Text("Остаток счёта всё равно изменится", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        localError?.let { P0Message(it) }
        P0FormStatus(state, viewModel)
        if (!deleted) P0Submit(if (original == null) "Добавить операцию ↗" else "Сохранить изменения ↗", "ledger_transaction_save", state.busy,
            editable && account != null && !account.archived && amount.isNotBlank()) {
            runCatching {
                val selected = requireNotNull(account) { "Выберите счёт" }
                val minor = Money.parseMinor(amount, SupportedCurrency.fromCode(selected.currency), positive = true)
                val occurred = p0Instant(date, time)
                if (original == null) viewModel.createTransaction(accountId, kind, minor, note.trim(), occurred, categoryId)
                else viewModel.updateTransaction(original, accountId, kind, minor, note.trim(), occurred, categoryId, excluded)
            }.onFailure { localError = it.message ?: "Проверьте данные" }
        }
        if (original != null) OutlinedButton(onClick = { viewModel.clearMessage(); confirm = if (deleted) "restore" else "delete" },
            enabled = state.canWrite && (!deleted || account?.archived == false), shape = P0Shape, contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxWidth().testTag(if (deleted) "ledger_transaction_restore" else "ledger_transaction_delete")) {
            Text(if (deleted) "Восстановить операцию" else "Удалить операцию")
        }
    }
    if (choosingAccount) P0OptionsSheet("С какого счёта?", state.accounts.filter { !it.archived || it.id == accountId }.map {
        P0Option(it.id, it.name, "${p0Money(it.balanceMinor, it.currency)}${if (it.archived) " · архив" else ""}") }, accountId,
        "ledger_transaction_accounts", { choosingAccount = false }) { accountId = it; choosingAccount = false }
}

@Composable
internal fun P0TransferEditor(state: LedgerUiState, viewModel: LedgerViewModel, original: MoneyTransfer?, onDismiss: () -> Unit) {
    val active = state.accounts.filterNot { it.archived }
    val stamp = remember { original?.occurredAt ?: Instant.now().toString() }
    var fromId by rememberSaveable(original?.id) { mutableStateOf(original?.fromAccountId ?: active.firstOrNull()?.id.orEmpty()) }
    var toId by rememberSaveable(original?.id) { mutableStateOf(original?.toAccountId ?: active.firstOrNull { it.id != fromId }?.id.orEmpty()) }
    var fromAmount by rememberSaveable(original?.id) { mutableStateOf(original?.let { p0Input(it.fromAmountMinor, it.fromCurrency) }.orEmpty()) }
    var toAmount by rememberSaveable(original?.id) { mutableStateOf(original?.let { p0Input(it.toAmountMinor, it.toCurrency) }.orEmpty()) }
    var hasFee by rememberSaveable(original?.id) { mutableStateOf((original?.feeAmountMinor ?: 0L) > 0L) }
    var feeAccountId by rememberSaveable(original?.id) { mutableStateOf(original?.feeAccountId ?: fromId) }
    var fee by rememberSaveable(original?.id) { mutableStateOf(original?.takeIf { it.feeAmountMinor > 0 }?.let { p0Input(it.feeAmountMinor, it.feeCurrency ?: it.fromCurrency) }.orEmpty()) }
    var note by rememberSaveable(original?.id) { mutableStateOf(original?.note.orEmpty()) }
    var date by rememberSaveable(original?.id) { mutableStateOf(p0LocalDate(stamp)) }
    var time by rememberSaveable(original?.id) { mutableStateOf(p0LocalTime(stamp)) }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    val revision = remember { state.revision }
    LaunchedEffect(state.revision) { if (state.revision != revision) onDismiss() }
    val from = state.accounts.firstOrNull { it.id == fromId }
    val to = state.accounts.firstOrNull { it.id == toId }
    val feeAccount = state.accounts.firstOrNull { it.id == feeAccountId }
    val deleted = original?.deletedAt != null
    val editable = state.canWrite && !deleted
    val sameCurrency = from != null && to != null && from.currency == to.currency
    val referencedArchived = listOfNotNull(from, to, feeAccount.takeIf { hasFee }).filter { it.archived }.distinctBy { it.id }
    if (confirm != null && original != null) {
        val restoring = confirm == "restore"
        P0Confirmation(if (restoring) "Вернуть весь перевод?" else "Удалить весь перевод?",
            if (restoring) "Списание, зачисление и комиссия восстановятся вместе. Остатки всех участвующих счетов будут пересчитаны."
            else "Списание, зачисление и комиссия будут удалены вместе. Остатки счетов пересчитаются. Перевод можно восстановить через фильтр удалённых операций.",
            if (restoring) "Восстановить перевод" else "Удалить перевод", "ledger_transfer_${if (restoring) "restore" else "delete"}",
            state, viewModel, { confirm = null }) {
            if (restoring) viewModel.restoreTransfer(original) else viewModel.deleteTransfer(original)
        }
        return
    }
    P0Sheet("ledger_transfer_editor", if (original == null) "Деньги в движении." else if (deleted) "Удалённый перевод." else "Детали перевода.",
        "Между своими счетами", state.busy, onDismiss) {
        P0Message(if (deleted) "Перевод удалён. Восстановление вернёт все его части вместе."
            else "Перевод не считается доходом или расходом. Только комиссия попадёт в расходы.")
        if (referencedArchived.isNotEmpty()) P0Message("Счета в архиве: ${referencedArchived.joinToString { it.name }}. ${if (deleted) "Перед восстановлением перевода верните их из архива." else "Верните их из архива или выберите действующие счета."}")
        P0Choice("01 / Откуда", from?.let { "${it.name} · ${it.currency}" } ?: "Выберите счёт", "ledger_transfer_from", editable) { picker = "from" }
        OutlinedTextField(fromAmount, { fromAmount = it; if (sameCurrency) toAmount = it }, label = { Text("Списано · ${from?.currency.orEmpty()}") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = editable,
            shape = P0Shape, textStyle = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth().testTag("ledger_transfer_from_amount"))
        Text("↓", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
        P0Choice("02 / Куда", to?.let { "${it.name} · ${it.currency}" } ?: "Выберите счёт", "ledger_transfer_to", editable) { picker = "to" }
        if (!sameCurrency) OutlinedTextField(toAmount, { toAmount = it }, label = { Text("Зачислено · ${to?.currency.orEmpty()}") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = editable,
            shape = P0Shape, textStyle = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth().testTag("ledger_transfer_to_amount"))
        else Text("На второй счёт поступит та же сумма. Комиссия указывается отдельно.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!sameCurrency) Text("Укажите реальные суммы из банка. Курс перевода определяется этими суммами и не меняется со временем.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Комиссия", style = MaterialTheme.typography.titleMedium)
                Text("Отдельная сумма сверх перевода", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(hasFee, { hasFee = it }, enabled = editable, modifier = Modifier.testTag("ledger_transfer_has_fee"))
        }
        if (hasFee) {
            P0Choice("Счёт комиссии", feeAccount?.let { "${it.name} · ${it.currency}" } ?: "Выберите счёт", "ledger_transfer_fee_account", editable) { picker = "fee" }
            OutlinedTextField(fee, { fee = it }, label = { Text("Комиссия · ${feeAccount?.currency.orEmpty()}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = editable,
                shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("ledger_transfer_fee_amount"))
            Text("Комиссия входит в расходы. По умолчанию — банковские услуги; если категория в архиве, без категории. Её можно оплатить с другого своего счёта.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        P0DateTimeFields(date, time, { date = it }, { time = it }, editable, "ledger_transfer")
        OutlinedTextField(note, { note = it.take(500) }, label = { Text("Описание перевода") }, minLines = 2, maxLines = 5,
            enabled = editable, shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("ledger_transfer_note"))
        localError?.let { P0Message(it) }
        P0FormStatus(state, viewModel)
        if (!deleted) P0Submit(if (original == null) "Записать перевод ↗" else "Сохранить весь перевод ↗", "ledger_transfer_save", state.busy,
            editable && from != null && to != null && fromId != toId && fromAmount.isNotBlank() && referencedArchived.isEmpty()) {
            runCatching {
                val source = requireNotNull(from) { "Выберите счёт списания" }
                val destination = requireNotNull(to) { "Выберите счёт зачисления" }
                require(fromId != toId) { "Выберите разные счета" }
                val sourceMinor = Money.parseMinor(fromAmount, SupportedCurrency.fromCode(source.currency), positive = true)
                val destinationMinor = if (sameCurrency) sourceMinor else Money.parseMinor(toAmount, SupportedCurrency.fromCode(destination.currency), positive = true)
                val feeMinor = if (hasFee) Money.parseMinor(fee, SupportedCurrency.fromCode(requireNotNull(feeAccount) { "Выберите счёт комиссии" }.currency), positive = true) else 0L
                viewModel.saveTransfer(original, fromId, toId, sourceMinor, destinationMinor, feeMinor,
                    if (hasFee) feeAccountId else null, note.trim(), p0Instant(date, time))
            }.onFailure { localError = it.message ?: "Проверьте перевод" }
        }
        if (original != null) OutlinedButton(onClick = { viewModel.clearMessage(); confirm = if (deleted) "restore" else "delete" },
            enabled = state.canWrite && (!deleted || referencedArchived.isEmpty()), shape = P0Shape, contentPadding = PaddingValues(16.dp),
            modifier = Modifier.fillMaxWidth().testTag(if (deleted) "ledger_transfer_restore" else "ledger_transfer_delete")) {
            Text(if (deleted) "Восстановить перевод" else "Удалить весь перевод")
        }
    }
    picker?.let { purpose ->
        val selected = when (purpose) { "from" -> fromId; "to" -> toId; else -> feeAccountId }
        val allowed = state.accounts.filter { (!it.archived || it.id == selected) && when (purpose) { "from" -> it.id != toId; "to" -> it.id != fromId; else -> true } }
        P0OptionsSheet(when (purpose) { "from" -> "Откуда перевести?"; "to" -> "Куда перевести?"; else -> "Счёт комиссии" },
            allowed.map { P0Option(it.id, it.name, p0Money(it.balanceMinor, it.currency)) }, selected,
            "ledger_transfer_${purpose}_accounts", { picker = null }) { id ->
            when (purpose) {
                "from" -> {
                    val feeWasSource = feeAccountId.isBlank() || feeAccountId == fromId
                    val oldCurrency = from?.currency
                    fromId = id; fromAmount = ""
                    if (feeWasSource) {
                        feeAccountId = id
                        if (state.accounts.firstOrNull { it.id == id }?.currency != oldCurrency) fee = ""
                    }
                }
                "to" -> { toId = id; toAmount = "" }
                else -> { feeAccountId = id; fee = "" }
            }
            picker = null
        }
    }
}
