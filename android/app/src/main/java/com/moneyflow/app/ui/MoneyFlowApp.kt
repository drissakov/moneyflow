package com.moneyflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.session?.user?.id) { dialog = null }

    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 22.dp, vertical = 12.dp),
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
                    IconButton(onClick = viewModel::refresh, enabled = !state.refreshing && !state.working,
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
        floatingActionButton = {
            if (state.session != null && state.selectedAccount != null && state.pending == null) {
                ExtendedFloatingActionButton(
                    onClick = { if (!state.working) { viewModel.clearMessages(); dialog = "transaction" } },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Добавить операцию") },
                    modifier = Modifier.testTag("add_transaction"),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.starting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.session == null -> AuthScreen(state, viewModel)
                else -> Dashboard(state, viewModel, onAddAccount = { viewModel.clearMessages(); dialog = "account" })
            }
        }
    }

    when (dialog) {
        "account" -> AccountDialog(state, viewModel) { dialog = null }
        "transaction" -> state.selectedAccount?.let { account -> TransactionDialog(account, state, viewModel) { dialog = null } }
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
        Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF123F36)),
        contentAlignment = Alignment.Center,
    ) {
        Text("M", color = Color(0xFF95EBC7), fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
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
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
private fun Dashboard(state: MoneyFlowUiState, viewModel: MoneyFlowViewModel, onAddAccount: () -> Unit) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 620.dp).fillMaxSize().testTag("dashboard"),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (state.refreshing || state.working) item {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
        }
        state.error?.let { error -> item { MessageCard(error, true, viewModel::clearMessages) } }
        state.notice?.let { notice -> item { MessageCard(notice, false, viewModel::clearMessages) } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Мои финансы", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Небольшие записи. Большая ясность.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { BalanceCard(state.selectedAccount, state.lastSyncedAt, state.refreshing) }
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
                } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.accounts.forEach { account ->
                        FilterChip(
                            selected = account.id == state.selectedAccountId,
                            onClick = { viewModel.selectAccount(account.id) },
                            label = { Column(Modifier.padding(vertical = 5.dp)) {
                                Text(account.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(Money.format(account.balanceMinor, account.currency), style = MaterialTheme.typography.labelSmall)
                            } },
                            leadingIcon = if (account.id == state.selectedAccountId) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                            modifier = Modifier.testTag("account_${account.id}"),
                        )
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
            items(state.transactions, key = { it.id }) { transaction -> TransactionRow(transaction) }
        }
    }
}

@Composable
private fun BalanceCard(account: Account?, lastSyncedAt: Instant?, refreshing: Boolean) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF123F36), Color(0xFF1C6951))))
            .padding(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(account?.name ?: "Первый шаг к порядку", color = Color(0xFFD0F1E3), style = MaterialTheme.typography.titleSmall)
            Text(account?.let { Money.format(it.balanceMinor, it.currency) } ?: "Создайте счёт",
                color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("account_balance"))
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            Text(
                when {
                    refreshing -> "Обновляем данные с сервера…"
                    lastSyncedAt != null -> "Данные на ${formatTime(lastSyncedAt.toString())} · доступны из кэша"
                    account != null -> "Сохранённые данные на устройстве · обновите для проверки"
                    else -> "Карта, наличные или сбережения — выберите свой счёт"
                },
                color = Color(0xFFD0F1E3), style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TransactionRow(transaction: MoneyTransaction) {
    val income = transaction.kind == "income"
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(
                    if (income) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text(if (income) "↙" else "↗", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(transaction.note.ifBlank { if (income) "Доход" else "Расход" },
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatTime(transaction.occurredAt), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
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
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Text("Валюта", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SupportedCurrency.entries.forEach { option ->
                        FilterChip(selected = option == currency, onClick = { currency = option },
                            label = { Text(option.code) }, enabled = !state.working, modifier = Modifier.testTag("currency_${option.code}"))
                    }
                }
                OutlinedTextField(opening, { opening = it }, label = { Text("Начальный остаток") },
                    suffix = { Text(currency.code) }, singleLine = true,
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

@Composable
private fun TransactionDialog(account: Account, state: MoneyFlowUiState, viewModel: MoneyFlowViewModel, dismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf("expense") }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val initialVersion = remember { state.mutationVersion }
    LaunchedEffect(state.mutationVersion) { if (state.mutationVersion != initialVersion) dismiss() }
    val pending = state.pending != null
    AlertDialog(
        onDismissRequest = { if (!state.working) dismiss() },
        title = { Text("Добавить операцию") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${account.name} · ${account.currency}", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == "expense", onClick = { kind = "expense" }, label = { Text("Расход") },
                        enabled = !state.working && !pending, modifier = Modifier.testTag("kind_expense"))
                    FilterChip(selected = kind == "income", onClick = { kind = "income" }, label = { Text("Доход") },
                        enabled = !state.working && !pending, modifier = Modifier.testTag("kind_income"))
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("Сумма") }, suffix = { Text(account.currency) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    enabled = !state.working && !pending, modifier = Modifier.fillMaxWidth().testTag("transaction_amount"))
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
                if (pending) viewModel.retryPending() else viewModel.createTransaction(kind, amount, note)
            }, enabled = !state.working, modifier = Modifier.testTag("transaction_submit")) {
                Text(if (state.working) "Сохраняем…" else if (pending) "Повторить" else "Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.working) { Text(if (pending) "Закрыть" else "Отмена") } },
    )
}

private fun formatTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM · HH:mm", Locale.forLanguageTag("ru-RU"))
        .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
