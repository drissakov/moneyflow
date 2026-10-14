package com.moneyflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneyflow.app.BuildConfig
import com.moneyflow.app.MoneyFlowApplication
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.MoneyTransaction
import com.moneyflow.app.data.domain.SupportedCurrency
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MoneyFlowApp(viewModel: MoneyFlowViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as MoneyFlowApplication
    val imports: ImportViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = ImportViewModel.factory(app, app.repository))
    val importState by imports.state.collectAsStateWithLifecycle()
    val importBlocksWrites = importState.pending != null || importState.recoveryUnavailable || importState.recoveryChecking ||
        (state.session != null && state.session?.token != importState.session?.token)
    SideEffect { imports.onMutation(viewModel::onImportedFinancialDataChanged) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var screen by rememberSaveable { mutableStateOf("overview") }
    var categoryTransactionId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.session?.user?.id) { dialog = null; screen = "overview"; categoryTransactionId = null }

    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("MoneyFlow", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        state.session?.user?.email ?: "Личные финансы · каждый день",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.session != null) {
                    IconButton(onClick = {
                        when (screen) {
                            "analytics" -> viewModel.refreshAnalytics()
                            "categories" -> viewModel.refreshCategories()
                            "imports" -> imports.refreshHistory()
                            else -> viewModel.refresh()
                        }
                    }, enabled = !state.refreshing && !state.working && !state.analyticsLoading && !state.categoriesLoading,
                        modifier = Modifier.testTag("refresh")) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить данные")
                    }
                    IconButton(onClick = { dialog = "logout" }, enabled = !state.working,
                        modifier = Modifier.testTag("logout")) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Выйти из аккаунта")
                    }
                }
            }
        },
        bottomBar = {
            if (state.session != null) Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        listOf(Triple("overview", "Обзор", NavigationIcons.Overview),
                            Triple("analytics", "Аналитика", NavigationIcons.Analytics),
                            Triple("categories", "Категории", NavigationIcons.Categories),
                            Triple("imports", "Импорт", NavigationIcons.Imports))
                            .forEach { (key, label, image) ->
                                NavigationBarItem(selected = screen == key, onClick = { screen = key },
                                    icon = { Icon(image, contentDescription = null, modifier = Modifier.size(22.dp)) },
                                    label = { Text(label, fontWeight = if (screen == key) FontWeight.SemiBold else FontWeight.Medium) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        indicatorColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ), modifier = Modifier.testTag("nav_$key"))
                            }
                    }
                    }
            }
        },
        floatingActionButton = {
            if (state.session != null && screen == "overview" && state.selectedAccount != null && state.pending == null && !importBlocksWrites) {
                ExtendedFloatingActionButton(
                    onClick = { if (!state.working) { viewModel.clearMessages(); dialog = "transaction" } },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Добавить операцию") },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.testTag("add_transaction"),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.starting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.session == null -> AuthScreen(state, viewModel)
                screen == "analytics" -> AnalyticsScreen(state, viewModel)
                screen == "categories" -> CategoriesScreen(state, viewModel)
                screen == "imports" -> ImportsScreen(importState, imports, state.pending != null)
                else -> Dashboard(state, viewModel,
                    onAddAccount = { if (importBlocksWrites) screen = "imports" else { viewModel.clearMessages(); dialog = "account" } },
                    onConverter = { viewModel.clearConversion(); dialog = "convert" },
                    onTransactionCategory = { transaction ->
                        viewModel.clearMessages(); viewModel.clearCategoryMessages()
                        categoryTransactionId = transaction.id; dialog = "assign_category"
                    })
            }
        }
    }

    when (dialog) {
        "account" -> AccountDialog(state, viewModel) { dialog = null }
        "transaction" -> state.selectedAccount?.let { account -> TransactionDialog(account, state, viewModel) { dialog = null } }
        "assign_category" -> state.transactions.firstOrNull { it.id == categoryTransactionId }?.let { transaction ->
            AssignCategoryDialog(transaction, state, viewModel) { dialog = null; categoryTransactionId = null }
        }
        "convert" -> ConverterDialog(state, viewModel) { dialog = null }
        "logout" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Локальная сессия и кэш финансовых данных будут удалены. Данные на сервере останутся в аккаунте." +
                if (state.pending != null) " Неподтверждённая операция сохранится в зашифрованном виде: войдите в этот аккаунт снова, чтобы подтвердить её." else "") },
            confirmButton = { TextButton(onClick = { dialog = null; viewModel.logout() }) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text("M", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
    }
}

@Composable
private fun AuthScreen(state: MoneyFlowUiState, viewModel: MoneyFlowViewModel) {
    var register by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Ваши деньги.\nВаш ясный план.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Счета, доходы и расходы в одном месте. Данные сохраняются в вашем аккаунте.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (register) "Создать аккаунт" else "Добро пожаловать", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, label = { Text("Email") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled = !state.working, modifier = Modifier.fillMaxWidth().testTag("auth_email"),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Пароль") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (register) { { Text("От 12 символов для латиницы; до 128 байт UTF-8") } } else null,
                    enabled = !state.working, modifier = Modifier.fillMaxWidth().testTag("auth_password"),
                )
                state.error?.let { MessageCard(it, true, viewModel::clearMessages) }
                state.notice?.let { MessageCard(it, false, viewModel::clearMessages) }
                Button(
                    onClick = { viewModel.authenticate(email, password, register) },
                    enabled = !state.working, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("auth_submit"),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (state.working) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Text(if (register) "Зарегистрироваться" else "Войти")
                }
                TextButton(
                    onClick = { register = !register; password = ""; viewModel.clearMessages() },
                    enabled = !state.working, modifier = Modifier.fillMaxWidth().testTag("auth_switch"),
                ) { Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Создать") }
            }
        }
        Text("Точность в каждой операции: суммы учитываются без округления.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (BuildConfig.DEBUG) Text("Разработка · ${BuildConfig.API_BASE_URL}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Dashboard(
    state: MoneyFlowUiState,
    viewModel: MoneyFlowViewModel,
    onAddAccount: () -> Unit,
    onConverter: () -> Unit,
    onTransactionCategory: (MoneyTransaction) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 620.dp).fillMaxSize().testTag("dashboard"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.refreshing || state.working) item {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let { error -> item { MessageCard(error, true, viewModel::clearMessages) } }
        state.notice?.let { notice -> item { MessageCard(notice, false, viewModel::clearMessages) } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Мои финансы", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Все счета — в одном балансе.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { PortfolioCard(state, viewModel, onConverter) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Счета", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onAddAccount, enabled = !state.working, modifier = Modifier.testTag("add_account")) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Новый счёт")
                    }
                }
                if (state.accounts.isEmpty()) {
                    EmptyCard("Начните с первого счёта", "Например, «Основная карта» или «Наличные». Выберите валюту и начальный остаток.")
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.accounts.forEach { account ->
                        FxAccountCard(account, state) { viewModel.selectAccount(account.id) }
                    }
                }
            }
        }
        state.pending?.let { pending -> item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ожидает подтверждения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val account = state.accounts.firstOrNull { it.id == pending.body.accountId }
                    val amount = account?.let { Money.format(pending.body.amountMinor.toLong(), it.currency) } ?: pending.body.amountMinor
                    Text("${if (pending.body.kind == "income") "Доход" else "Расход"} · $amount${account?.let { " · ${it.name}" }.orEmpty()}")
                    if (pending.body.note.isNotBlank()) Text(pending.body.note, style = MaterialTheme.typography.bodySmall)
                    Text("Повторная отправка использует тот же ключ: сервер сохранит одну операцию.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = viewModel::retryPending, enabled = !state.working, modifier = Modifier.testTag("retry_pending")) {
                        Text("Повторить")
                    }
                }
            }
        } }
        if (state.selectedAccount != null) {
            item { BalanceCard(state.selectedAccount, state.lastSyncedAt, state.refreshing) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Последние операции", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("До 50 записей по выбранному счёту", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.transactions.isEmpty()) item {
                EmptyCard("Здесь появятся ваши записи", "Добавьте первый расход или доход. Баланс обновится после сохранения на сервере.")
            }
            items(state.transactions, key = { it.id }) { transaction ->
                TransactionRow(transaction, state, onSelectCategory = { onTransactionCategory(transaction) })
            }
        }
    }
}

@Composable
private fun BalanceCard(account: Account?, lastSyncedAt: Instant?, refreshing: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = colors.surface, contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Выбранный счёт", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(account?.name ?: "Создайте счёт", style = MaterialTheme.typography.titleMedium)
            Text(account?.let { Money.format(it.balanceMinor, it.currency) } ?: "Создайте счёт",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("account_balance"))
            Text(
                when {
                    refreshing -> "Обновляем данные с сервера…"
                    lastSyncedAt != null -> "Данные на ${formatTime(lastSyncedAt.toString())} · доступны из кэша"
                    account != null -> "Сохранённые данные на устройстве · обновите для проверки"
                    else -> "Карта, наличные или сбережения — выберите свой счёт"
                },
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TransactionRow(transaction: MoneyTransaction, state: MoneyFlowUiState, onSelectCategory: () -> Unit) {
    val income = transaction.kind == "income"
    Card(onClick = onSelectCategory, enabled = !state.working,
        modifier = Modifier.fillMaxWidth().testTag("transaction_${transaction.id}"),
        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(
                    if (income) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text(if (income) "↙" else "↗", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(transaction.note.ifBlank { if (transaction.excludeFromAnalytics) "Движение денег" else if (income) "Доход" else "Расход" },
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatTime(transaction.occurredAt), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (transaction.excludeFromAnalytics) Text("Движение денег · вне аналитики", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${categoryPath(transaction.categoryId, state.categories)} ›", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("transaction_category_path_${transaction.id}"))
            }
            Spacer(Modifier.width(8.dp))
            Text("${if (income) "+" else "−"}${Money.format(transaction.amountMinor, transaction.currency)}",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                color = if (income) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun EmptyCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MessageCard(message: String, error: Boolean, dismiss: () -> Unit) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(1.dp, if (error) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (error) Icons.Outlined.Warning else Icons.Outlined.Info,
                contentDescription = if (error) "Ошибка" else "Информация", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = dismiss) { Icon(Icons.Default.Close, contentDescription = "Закрыть сообщение", modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun AccountDialog(state: MoneyFlowUiState, viewModel: MoneyFlowViewModel, dismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf(SupportedCurrency.KZT) }
    var opening by rememberSaveable { mutableStateOf("0") }
    val initialVersion = remember { state.mutationVersion }
    LaunchedEffect(state.mutationVersion) { if (state.mutationVersion != initialVersion) dismiss() }
    AlertDialog(
        onDismissRequest = { if (!state.working) dismiss() },
        title = { Text("Новый счёт") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Отдельный счёт для карты, наличных или сбережений.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it }, label = { Text("Название счёта") }, singleLine = true,
                    enabled = !state.working, modifier = Modifier.fillMaxWidth().testTag("account_name"))
                CurrencySelector("Валюта счёта · 11 валют", currency, { currency = it },
                    modifier = Modifier.fillMaxWidth().testTag("account_currency"), enabled = !state.working)
                OutlinedTextField(opening, { opening = it }, label = { Text("Начальный остаток") },
                    suffix = { Text(currency.code) }, singleLine = true,
                    trailingIcon = { TextButton(onClick = {
                        val value = opening.trim()
                        opening = if (value.startsWith("-")) value.drop(1) else "-$value"
                    }, enabled = !state.working) { Text("±") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("${currency.scale} знаков после запятой; может быть отрицательным") },
                    enabled = !state.working, modifier = Modifier.fillMaxWidth().testTag("account_opening"))
                state.error?.let { MessageCard(it, true, viewModel::clearMessages) }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.createAccount(name, currency, opening) }, enabled = !state.working,
                modifier = Modifier.testTag("account_submit")) { Text(if (state.working) "Сохраняем…" else "Создать") }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.working) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TransactionDialog(account: Account, state: MoneyFlowUiState, viewModel: MoneyFlowViewModel, dismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf("expense") }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingCategory by rememberSaveable { mutableStateOf(false) }
    val initialVersion = remember { state.mutationVersion }
    LaunchedEffect(state.mutationVersion) { if (state.mutationVersion != initialVersion) dismiss() }
    val pending = state.pending != null
    AlertDialog(
        onDismissRequest = { if (!state.working) dismiss() },
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text("Добавить операцию") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${account.name} · ${account.currency}", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == "expense", onClick = { if (kind != "expense") categoryId = null; kind = "expense" }, label = { Text("Расход") },
                        enabled = !state.working && !pending, modifier = Modifier.testTag("kind_expense"))
                    FilterChip(selected = kind == "income", onClick = { if (kind != "income") categoryId = null; kind = "income" }, label = { Text("Доход") },
                        enabled = !state.working && !pending, modifier = Modifier.testTag("kind_income"))
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("Сумма") }, suffix = { Text(account.currency) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    enabled = !state.working && !pending, modifier = Modifier.fillMaxWidth().testTag("transaction_amount"))
                CategorySelector(state.categories, kind, categoryId, { categoryId = it },
                    onCreate = { viewModel.clearCategoryMessages(); creatingCategory = true },
                    enabled = !state.working && !pending && !state.categoryWorking)
                if (state.categoriesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!creatingCategory) state.categoryError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::refreshCategories, enabled = !state.categoriesLoading) { Text("Загрузить категории") }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Комментарий (необязательно)") }, minLines = 2, maxLines = 4,
                    enabled = !state.working && !pending, modifier = Modifier.fillMaxWidth().testTag("transaction_note"))
                Text("Дата операции — сейчас. Баланс изменится после подтверждения сервером.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { MessageCard(it, true, viewModel::clearMessages) }
                if (pending && !state.working) Text("Ответ ещё не подтверждён. Повторная отправка безопасна: ключ операции сохранён.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (pending) viewModel.retryPending() else viewModel.createTransaction(kind, amount, note, categoryId)
            }, enabled = !state.working && !state.categoryWorking, modifier = Modifier.testTag("transaction_submit")) {
                Text(if (state.working) "Сохраняем…" else if (pending) "Повторить" else "Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.working && !state.categoryWorking) { Text(if (pending) "Закрыть" else "Отмена") } },
    )
    if (creatingCategory) CreateCategoryDialog(state, viewModel, kind, dismiss = { creatingCategory = false }, onCreated = { categoryId = it })
}

private fun formatTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM · HH:mm", Locale.forLanguageTag("ru-RU"))
        .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
