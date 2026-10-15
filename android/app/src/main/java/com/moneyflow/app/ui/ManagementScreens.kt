package com.moneyflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.Category
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.SupportedCurrency

private val accountTypes = listOf("card" to "Банковская карта", "cash" to "Наличные", "savings" to "Депозит / накопления",
    "credit" to "Кредитный счёт", "other" to "Другой счёт")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccountsManagementScreen(state: LedgerUiState, viewModel: LedgerViewModel) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val accounts = state.accounts.filter { showArchived || !it.archived }.sortedWith(compareBy<Account> { it.sortOrder }.thenBy { it.createdAt }.thenBy { it.id })
    LaunchedEffect(state.session?.user?.id) { editing = false; selectedId = null }
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("accounts_management_screen"),
        contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { P0Heading("03", "Мои деньги.\nМои счета.", "Карты, наличные и накопления — со своим местом в общей картине.") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.clearMessage(); selectedId = null; editing = true }, enabled = state.canWrite,
                    shape = P0Shape, contentPadding = PaddingValues(16.dp), modifier = Modifier.testTag("accounts_add")) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text(" Новый счёт")
                }
                FilterChip(showArchived, { showArchived = !showArchived }, label = { Text("Архив") }, modifier = Modifier.testTag("accounts_show_archived"))
                TextButton(onClick = viewModel::refresh, enabled = !state.loading && !state.busy) { Text("Обновить") }
            }
        }
        item { P0ManagementStatus(state, viewModel) }
        if (accounts.isEmpty() && !state.loading) item { P0Message("Счетов пока нет. Добавьте карту или наличные, чтобы начать учёт.") }
        items(accounts, key = { it.id }) { account ->
            Surface(shape = P0Shape, color = if (account.archived) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) { viewModel.clearMessage(); selectedId = account.id; editing = true }
                    .testTag("account_manage_${account.id}")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = P0Shape, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Text(account.currency, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(12.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(account.name, style = MaterialTheme.typography.titleLarge)
                            Text(accountTypes.firstOrNull { it.first == account.type }?.second ?: "Счёт", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("↗", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(p0Money(account.balanceMinor, account.currency), style = MaterialTheme.typography.headlineMedium)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text((if (account.archived) "В АРХИВЕ · " else "") + if (account.includeInBalance) "В ОБЩЕМ БАЛАНСЕ" else "НЕ ВХОДИТ В ОБЩИЙ БАЛАНС",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (editing) P0AccountEditor(state, viewModel, state.accounts.firstOrNull { it.id == selectedId }) { editing = false; selectedId = null }
}

@Composable
private fun P0ManagementStatus(state: LedgerUiState, viewModel: LedgerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.externalBlocked) P0Message("Сначала завершите проверку неподтверждённой операции на главной или в импорте.")
        if (state.pending != null || state.manualPending || state.recoveryUnavailable) {
            P0Message("Есть неподтверждённое действие. Проверьте его результат перед следующим изменением.")
            OutlinedButton(onClick = viewModel::retryPending, enabled = !state.busy && !state.externalBlocked && !state.recoveryChecking && !state.recoveryUnavailable,
                modifier = Modifier.testTag("management_retry_pending")) { Text("Проверить действие") }
        }
        state.error?.let { P0Message(it, viewModel::clearMessage) }
        state.notice?.let { P0Message(it, viewModel::clearMessage) }
    }
}

@Composable
internal fun P0AccountEditor(state: LedgerUiState, viewModel: LedgerViewModel, original: Account?, onDismiss: () -> Unit) {
    var name by rememberSaveable(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var currency by rememberSaveable(original?.id) { mutableStateOf(original?.currency ?: "KZT") }
    var type by rememberSaveable(original?.id) { mutableStateOf(original?.type ?: "card") }
    var opening by rememberSaveable(original?.id) { mutableStateOf(p0Input(original?.openingBalanceMinor ?: 0L, currency)) }
    var include by rememberSaveable(original?.id) { mutableStateOf(original?.includeInBalance ?: true) }
    var order by rememberSaveable(original?.id) { mutableStateOf((original?.sortOrder ?: 0).toString()) }
    var picker by remember { mutableStateOf<String?>(null) }
    var archiveConfirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val revision = remember { state.revision }
    LaunchedEffect(state.revision) { if (state.revision != revision) onDismiss() }
    if (archiveConfirm && original != null) {
        P0Confirmation(if (original.archived) "Вернуть счёт?" else "Убрать счёт в архив?",
            if (original.archived) "Счёт снова станет доступен для операций и переводов. Его история сохранена."
            else "История и настройка «В общем балансе» сохранятся. Новые операции недоступны до восстановления. Счёт можно вернуть из архива в любой момент.",
            if (original.archived) "Вернуть счёт" else "В архив", "account_archive", state, viewModel, { archiveConfirm = false }) {
            viewModel.saveAccount(original, original.name, original.type, original.openingBalanceMinor,
                !original.archived, original.includeInBalance, original.sortOrder)
        }
        return
    }
    P0Sheet("account_management_editor", if (original == null) "Место для денег." else "Настроить счёт.",
        if (original == null) "Счета / добавить" else "Счета / изменить", state.busy, onDismiss) {
        OutlinedTextField(name, { name = it.take(100) }, label = { Text("Название счёта") }, singleLine = true, enabled = state.canWrite,
            shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("account_manage_name"))
        P0Choice("Тип счёта", accountTypes.firstOrNull { it.first == type }?.second ?: "Счёт", "account_manage_type", state.canWrite) { picker = "type" }
        P0Choice("Валюта", SupportedCurrency.fromCode(currency).let { "${it.symbol} · ${it.title}" }, "account_manage_currency", state.canWrite && original == null) { picker = "currency" }
        if (original != null) Text("Валюта существующего счёта сохраняется, чтобы история и остаток оставались корректными.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(opening, { opening = it }, label = { Text("Начальный остаток · $currency") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = state.canWrite,
            shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("account_manage_opening"))
        FilterChip(opening.trim().startsWith("-"), onClick = {
            opening = if (opening.trim().startsWith("-")) opening.trim().removePrefix("-") else "-${opening.trim().ifBlank { "0" }}"
        }, label = { Text("Отрицательный остаток") }, enabled = state.canWrite, modifier = Modifier.testTag("account_manage_negative"))
        Text("Сумма до первой операции. Изменение начального остатка пересчитает текущий баланс. Для кредитного долга можно указать отрицательное значение.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (original != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("В общем балансе", style = MaterialTheme.typography.titleMedium)
                    Text("Учитывать при пересчёте всех валют", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(include, { include = it }, enabled = state.canWrite, modifier = Modifier.testTag("account_manage_include"))
            }
            OutlinedTextField(order, { order = it }, label = { Text("Порядок в списке") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = state.canWrite,
                supportingText = { Text("Меньше число — выше в списке") }, shape = P0Shape,
                modifier = Modifier.fillMaxWidth().testTag("account_manage_order"))
        }
        error?.let { P0Message(it) }; P0FormStatus(state, viewModel)
        P0Submit(if (original == null) "Создать счёт ↗" else "Сохранить изменения ↗", "account_manage_save", state.busy,
            state.canWrite && name.trim().isNotEmpty()) {
            runCatching {
                val minor = Money.parseMinor(opening, SupportedCurrency.fromCode(currency))
                if (original == null) viewModel.createAccount(name.trim(), currency, minor, type)
                else viewModel.saveAccount(original, name.trim(), type, minor, original.archived, include,
                    order.toIntOrNull() ?: throw IllegalArgumentException("Порядок должен быть целым числом"))
            }.onFailure { error = it.message ?: "Проверьте данные счёта" }
        }
        if (original != null) OutlinedButton(onClick = { viewModel.clearMessage(); archiveConfirm = true }, enabled = state.canWrite,
            shape = P0Shape, contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth().testTag("account_manage_archive")) {
            Text(if (original.archived) "Вернуть из архива" else "Архивировать счёт")
        }
    }
    when (picker) {
        "type" -> P0OptionsSheet("Тип счёта", accountTypes.map { P0Option(it.first, it.second) }, type, "account_manage_types",
            { picker = null }) { type = it; picker = null }
        "currency" -> P0OptionsSheet("Валюта счёта", SupportedCurrency.entries.map { P0Option(it.code, "${it.symbol} · ${it.code}", it.title) },
            currency, "account_manage_currencies", { picker = null }) { currency = it; opening = "0"; picker = null }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoriesManagementScreen(state: LedgerUiState, viewModel: LedgerViewModel) {
    var kind by rememberSaveable { mutableStateOf("expense") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var parentId by rememberSaveable { mutableStateOf<String?>(null) }
    val roots = state.categories.filter { it.kind == kind && it.parentId == null && (archived || !it.archived) }
        .sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.name })
    LaunchedEffect(state.session?.user?.id) { editing = false; selectedId = null; parentId = null }
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("categories_management_screen"),
        contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { P0Heading("05", "У всего\nесть своё место.", "Своя система категорий. Без лишних рамок.") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(kind == "expense", { kind = "expense" }, label = { Text("Расходы") }, modifier = Modifier.testTag("manage_categories_expense"))
                FilterChip(kind == "income", { kind = "income" }, label = { Text("Доходы") }, modifier = Modifier.testTag("manage_categories_income"))
                FilterChip(archived, { archived = !archived }, label = { Text("Архив") }, modifier = Modifier.testTag("manage_categories_archived"))
            }
            Button(onClick = { viewModel.clearMessage(); selectedId = null; parentId = null; editing = true }, enabled = state.canWrite,
                shape = P0Shape, contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth().testTag("manage_category_add")) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text(" Новая категория")
            }
        }
        item { P0ManagementStatus(state, viewModel) }
        if (roots.isEmpty() && !state.loading) item { P0Message("Пока нет категорий. Добавьте свою — с понятным вам названием.") }
        items(roots, key = { it.id }) { root ->
            val children = state.categories.filter { it.parentId == root.id && (archived || !it.archived) }
                .sortedWith(compareBy<Category> { it.sortOrder }.thenBy { it.name })
            Surface(shape = P0Shape, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(6.dp)) {
                    P0CategoryManageRow(root, false, !state.busy) { viewModel.clearMessage(); selectedId = root.id; parentId = null; editing = true }
                    children.forEach { child ->
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        P0CategoryManageRow(child, true, !state.busy) { viewModel.clearMessage(); selectedId = child.id; parentId = null; editing = true }
                    }
                    if (kind == "expense" && !root.archived) TextButton(onClick = { viewModel.clearMessage(); selectedId = null; parentId = root.id; editing = true },
                        enabled = state.canWrite, modifier = Modifier.padding(horizontal = 8.dp).testTag("manage_category_add_child_${root.id}")) { Text("+ Подкатегория") }
                }
            }
        }
    }
    if (editing) P0CategoryEditor(state, viewModel, state.categories.firstOrNull { it.id == selectedId }, kind, parentId) { editing = false; selectedId = null; parentId = null }
}

@Composable
private fun P0CategoryManageRow(category: Category, child: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp).testTag("manage_category_${category.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (child) "↳" else "#", style = if (child) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(category.name, style = if (child) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge)
            Text(if (category.archived) "В архиве" else if (category.isDefault) "Стандартная · можно настроить" else "Ваша категория",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("↗", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun P0CategoryEditor(state: LedgerUiState, viewModel: LedgerViewModel, original: Category?, kind: String,
    initialParentId: String? = null, onDismiss: () -> Unit) {
    var name by rememberSaveable(original?.id, initialParentId) { mutableStateOf(original?.name.orEmpty()) }
    var parentId by rememberSaveable(original?.id, initialParentId) { mutableStateOf(original?.parentId ?: initialParentId) }
    var order by rememberSaveable(original?.id) { mutableStateOf((original?.sortOrder ?: 0).toString()) }
    var pickingParent by remember { mutableStateOf(false) }
    var archiveConfirm by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val revision = remember { state.revision }
    val archivedParent = state.categories.firstOrNull { it.id == original?.parentId && it.archived }
    LaunchedEffect(state.revision) { if (state.revision != revision) onDismiss() }
    if (merging && original != null) { P0CategoryMerge(state, viewModel, original) { merging = false }; return }
    if (archiveConfirm && original != null) {
        val hasChildren = state.categories.any { it.parentId == original.id }
        P0Confirmation(if (original.archived) "Вернуть категорию?" else "Архивировать категорию?",
            if (original.archived) "Категория снова станет доступна при добавлении операций. История сохранена."
            else "Название останется в истории и отчётах. Категория${if (hasChildren) " и её подкатегории" else ""} станет недоступна для новых операций. Позже её можно вернуть из архива.",
            if (original.archived) "Вернуть категорию" else "В архив", "category_archive", state, viewModel, { archiveConfirm = false }) {
            viewModel.saveCategory(original, original.name, !original.archived, original.sortOrder)
        }; return
    }
    P0Sheet("category_management_editor", if (original == null) { if (parentId == null) "Новая категория." else "Точнее о тратах." } else "Своя система.",
        if (kind == "income") "Категории / доходы" else "Категории / расходы", state.busy, onDismiss) {
        OutlinedTextField(name, { name = it.take(100) }, label = { Text("Название") }, singleLine = true, enabled = state.canWrite,
            shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("category_manage_name"))
        if (kind == "expense") {
            P0Choice("Родительская категория", state.categories.firstOrNull { it.id == parentId }?.name ?: "Основная категория",
                "category_manage_parent", state.canWrite && original == null) { pickingParent = true }
            if (original != null) Text("Уровень существующей категории сохраняется. Для переноса операций используйте объединение.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text("Доходы учитываются без подкатегорий.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (original != null) {
            OutlinedTextField(order, { order = it }, label = { Text("Порядок в списке") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = state.canWrite,
                supportingText = { Text("Меньше число — выше в списке") }, shape = P0Shape,
                modifier = Modifier.fillMaxWidth().testTag("category_manage_order"))
            if (original.isDefault) P0Message("Изменения стандартной категории действуют только в вашем аккаунте.")
            if (original.archived && archivedParent != null) P0Message("Сначала верните родительскую категорию «${archivedParent.name}» из архива.")
        }
        error?.let { P0Message(it) }; P0FormStatus(state, viewModel)
        P0Submit(if (original == null) "Создать категорию ↗" else "Сохранить изменения ↗", "category_manage_save", state.busy,
            state.canWrite && name.trim().isNotEmpty()) {
            runCatching {
                if (original == null) viewModel.createCategory(kind, name.trim(), if (kind == "expense") parentId else null)
                else viewModel.saveCategory(original, name.trim(), original.archived,
                    order.toIntOrNull() ?: throw IllegalArgumentException("Порядок должен быть целым числом"))
            }.onFailure { error = it.message ?: "Проверьте данные категории" }
        }
        if (original != null) {
            if (!original.archived) OutlinedButton(onClick = { viewModel.clearMessage(); merging = true }, enabled = state.canWrite,
                shape = P0Shape, contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth().testTag("category_manage_merge")) { Text("Объединить с другой категорией") }
            OutlinedButton(onClick = { viewModel.clearMessage(); archiveConfirm = true }, enabled = state.canWrite && (!original.archived || archivedParent == null),
                shape = P0Shape, contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth().testTag("category_manage_archive")) {
                Text(if (original.archived) "Вернуть из архива" else "Архивировать категорию")
            }
        }
    }
    if (pickingParent) P0OptionsSheet("Уровень категории", listOf(P0Option("", "Основная категория", "Без родительской категории")) +
        state.categories.filter { it.kind == "expense" && it.parentId == null && !it.archived }.map { P0Option(it.id, it.name, "Подкатегория расходов") },
        parentId ?: "", "category_manage_parents", { pickingParent = false }) { parentId = it.ifBlank { null }; pickingParent = false }
}

@Composable
private fun P0CategoryMerge(state: LedgerUiState, viewModel: LedgerViewModel, source: Category, onDismiss: () -> Unit) {
    var targetId by rememberSaveable(source.id) { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val forbidden = state.categories.filter { it.id == source.id || it.parentId == source.id }.map { it.id }.toSet()
    val targets = state.categories.filter { it.kind == source.kind && !it.archived && it.id !in forbidden }
    val target = targets.firstOrNull { it.id == targetId }
    if (confirming && target != null) {
        P0Confirmation("Объединить категории?", "Все операции категории «${source.name}» и её подкатегорий перейдут в «${categoryPath(target.id, state.categories)}». Исходная категория и её подкатегории будут архивированы. Автоматически отменить объединение нельзя.",
            "Объединить историю", "category_merge", state, viewModel, { confirming = false }) { viewModel.mergeCategory(source, target) }
        return
    }
    P0Sheet("category_merge_sheet", "Меньше категорий.\nБольше порядка.", "Категории / объединение", state.busy, onDismiss) {
        Text("Из категории", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(categoryPath(source.id, state.categories), style = MaterialTheme.typography.headlineSmall)
        P0Choice("В категорию", target?.let { categoryPath(it.id, state.categories) } ?: "Выберите, куда перенести историю", "category_merge_target", state.canWrite) { picking = true }
        Text("История исходной категории, включая подкатегории, будет перенесена целиком. Объединяются только категории одного типа.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (targets.isEmpty()) P0Message("Нет подходящей категории. Сначала создайте другую категорию того же типа.")
        P0FormStatus(state, viewModel)
        P0Submit("Продолжить ↗", "category_merge_continue", state.busy, state.canWrite && target != null) { confirming = true }
    }
    if (picking) P0OptionsSheet("Куда перенести историю?", targets.map { P0Option(it.id, categoryPath(it.id, state.categories)) },
        targetId, "category_merge_targets", { picking = false }) { targetId = it; picking = false }
}
