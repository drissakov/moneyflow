package com.moneyflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.SupportedCurrency
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CurrencySelector(
    label: String,
    selected: SupportedCurrency,
    onSelect: (SupportedCurrency) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tagPrefix: String = "currency",
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled,
            shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${selected.code} · ${selected.title}", maxLines = 2, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Выбрать валюту")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 350.dp).semantics { testTagsAsResourceId = true }) {
            SupportedCurrency.entries.forEach { currency ->
                DropdownMenuItem(
                    text = { Text("${currency.code} · ${currency.title}") },
                    onClick = { expanded = false; onSelect(currency) },
                    modifier = Modifier.testTag("${tagPrefix}_${currency.code}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun PortfolioCard(state: MoneyFlowUiState, viewModel: MoneyFlowViewModel, openConverter: () -> Unit) {
    val portfolio = state.portfolio
    val stale = portfolio?.ratesStale(state.fxNow) == true
    val colors = MaterialTheme.colorScheme
    val dates = portfolio?.accounts.orEmpty().mapNotNull { it.rateDate }.distinct().sorted()
    val usesRates = dates.isNotEmpty()
    var currencyMenu by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    val included = state.accounts.filter { it.includeInBalance }
    val accountCount = included.size
    val currencyCount = included.map { it.currency }.distinct().size
    val countLabel = "$accountCount ${countWord(accountCount, "счёт", "счёта", "счетов")} · $currencyCount ${countWord(currencyCount, "валюта", "валюты", "валют")}"
    Surface(shape = P0Shape, color = colors.primary, contentColor = colors.onPrimary,
        modifier = Modifier.fillMaxWidth().testTag("portfolio_card")) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("КАПИТАЛ / СЕГОДНЯ", style = MaterialTheme.typography.labelSmall,
                        color = colors.onPrimary.copy(alpha = .65f))
                    Text("Общий баланс", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                }
                Box {
                    OutlinedButton(onClick = { currencyMenu = true }, enabled = !state.working,
                        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onPrimary,
                            disabledContentColor = colors.onPrimary.copy(alpha = 0.45f)),
                        border = BorderStroke(1.dp, colors.onPrimary.copy(alpha = 0.35f)),
                        modifier = Modifier.testTag("base_currency").semantics {
                            contentDescription = "Валюта общего баланса: ${state.baseCurrency}. Изменить валюту"
                        }) {
                        Text(state.baseCurrency, style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(currencyMenu, onDismissRequest = { currencyMenu = false },
                        modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                        SupportedCurrency.entries.forEach { currency ->
                            DropdownMenuItem(text = { Text("${currency.code} · ${currency.title}") },
                                onClick = { currencyMenu = false; viewModel.setBaseCurrency(currency.code) },
                                modifier = Modifier.testTag("base_${currency.code}"))
                        }
                    }
                }
            }
            when {
                portfolio?.complete == true -> {
                    Text((if (usesRates) "≈ " else "") + Money.format(requireNotNull(portfolio.totalBalanceMinor), state.baseCurrency),
                        style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("portfolio_total"))
                }
                portfolio != null -> {
                    StatusBadge("Неполная оценка", true)
                    Text("Учтённая часть: ${Money.format(portfolio.partialBalanceMinor, state.baseCurrency)}",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("portfolio_partial"))
                    Text("Нет курса для ${portfolio.missingCurrencies.joinToString()}. Эта сумма не включает все счета.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onPrimary.copy(alpha = 0.8f))
                }
                else -> {
                    Text("Оценка недоступна", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("portfolio_unavailable"))
                    Text(when {
                        !state.accountsLoaded -> "Загружаем счета…"
                        state.cachedPortfolio?.portfolio?.ratesExpired(state.fxNow) == true -> "Сохранённые курсы старше 7 дней. Обновите оценку."
                        state.cachedPortfolio != null -> "Остатки изменились. Нужна оценка для актуальных счетов."
                        state.fxRefreshing -> "Пересчитываем все счета…"
                        else -> "Обновите баланс. Остатки счетов доступны ниже."
                    }, style = MaterialTheme.typography.bodySmall, color = colors.onPrimary.copy(alpha = 0.8f))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(countLabel, style = MaterialTheme.typography.bodyMedium, color = colors.onPrimary.copy(alpha = 0.78f),
                    modifier = Modifier.weight(1f))
                androidx.compose.foundation.Canvas(Modifier.size(width = 52.dp, height = 28.dp)) {
                    listOf(1f, 3f, 4f, 7f, 9f, 10f, 13f, 16f, 17f, 20f, 22f).forEachIndexed { index, x ->
                        val px = x / 23f * size.width
                        drawLine(colors.onPrimary.copy(alpha = .6f), androidx.compose.ui.geometry.Offset(px, if (index % 3 == 0) 0f else size.height * .3f),
                            androidx.compose.ui.geometry.Offset(px, size.height), if (index % 2 == 0) 2.dp.toPx() else 1.dp.toPx())
                    }
                }
            }
            if (included.size < state.accounts.size) Text("Вне общего баланса: ${state.accounts.size - included.size}",
                style = MaterialTheme.typography.labelSmall, color = colors.onPrimary.copy(alpha = .78f))
            if (state.basePreferencePending) StatusBadge("Выбор сохранён на устройстве · ожидает синхронизации", false)
            if (portfolio != null) {
                if (stale) StatusBadge("Устаревшие курсы · оценка приблизительная", true)
                else if (state.lastFxConfirmedAt == null) StatusBadge("Сохранённая оценка из кэша", false)
                Text(when {
                    !portfolio.complete && dates.isEmpty() -> "Курсы конвертации недоступны"
                    dates.isEmpty() -> "Все счета в ${state.baseCurrency} · конвертация не нужна"
                    else -> "Frankfurter · курсы ${rateDatesLabel(dates)}"
                }, style = MaterialTheme.typography.labelSmall, color = colors.onPrimary.copy(alpha = 0.78f))
            }
            state.fxError?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = "Ошибка обновления", modifier = Modifier.size(18.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).testTag("fx_error"))
                }
            }
            HorizontalDivider(color = colors.onPrimary.copy(alpha = 0.18f))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = viewModel::refreshFx, enabled = !state.fxRefreshing,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onPrimary,
                        disabledContentColor = colors.onPrimary.copy(alpha = 0.45f)),
                    modifier = Modifier.testTag("refresh_fx")) { Text("Обновить курсы") }
                TextButton(onClick = openConverter, colors = ButtonDefaults.textButtonColors(contentColor = colors.onPrimary),
                    modifier = Modifier.testTag("open_converter")) { Text("Конвертер") }
                if (state.fxRefreshing) CircularProgressIndicator(Modifier.size(20.dp), color = colors.onPrimary, strokeWidth = 2.dp)
            }
            TextButton(onClick = { details = !details }, colors = ButtonDefaults.textButtonColors(contentColor = colors.onPrimary.copy(alpha = 0.78f)),
                modifier = Modifier.testTag("portfolio_details")) { Text(if (details) "Скрыть расчёт" else "Как рассчитан баланс", style = MaterialTheme.typography.labelMedium) }
            if (details) {
                Text("Остатки счетов с включённым параметром «В общем балансе» пересчитываются в ${state.baseCurrency} и складываются. Архивирование само по себе не исключает счёт из расчёта.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onPrimary.copy(alpha = 0.8f))
                Text("Справочная оценка по ежедневным курсам. Курс обмена в вашем банке может отличаться; деньги на счетах остаются в исходных валютах.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onPrimary.copy(alpha = 0.8f))
                portfolio?.fetchedAt?.let { Text("Курсы получены: ${fxTimestamp(it)}", style = MaterialTheme.typography.labelSmall,
                    color = colors.onPrimary.copy(alpha = 0.78f)) }
                portfolio?.accounts?.forEach { account ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(account.name, style = MaterialTheme.typography.labelLarge)
                        Text("${Money.format(account.balanceMinor, account.currency)} → ${account.convertedBalanceMinor?.let { Money.format(it, state.baseCurrency) } ?: "нет курса"}",
                            style = MaterialTheme.typography.bodySmall, color = colors.onPrimary.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

private fun countWord(count: Int, one: String, few: String, many: String): String = when {
    count % 100 in 11..14 -> many
    count % 10 == 1 -> one
    count % 10 in 2..4 -> few
    else -> many
}

private fun rateDatesLabel(dates: List<String>): String {
    val format = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    val first = LocalDate.parse(dates.first()).format(format)
    return if (dates.size == 1) first else "$first — ${LocalDate.parse(dates.last()).format(format)}"
}

@Composable
internal fun FxAccountCard(account: Account, state: MoneyFlowUiState, onSelect: () -> Unit) {
    val selected = state.selectedAccountId == account.id
    val estimate = state.portfolio?.accounts?.firstOrNull { it.id == account.id }
    Card(onClick = onSelect, shape = P0Shape,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().testTag("account_${account.id}").semantics { this.selected = selected }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(account.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(account.currency, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
            }
            Text(Money.format(account.balanceMinor, account.currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            when {
                !account.includeInBalance -> Text("Вне общего баланса", style = MaterialTheme.typography.labelSmall)
                account.currency == state.baseCurrency -> Text("${state.baseCurrency} · основная валюта",
                    style = MaterialTheme.typography.labelSmall)
                estimate?.convertedBalanceMinor != null -> {
                    Text("≈ ${Money.format(estimate.convertedBalanceMinor, state.baseCurrency)} · котировка ${estimate.rateDate}",
                        style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        modifier = Modifier.testTag("account_estimate_${account.id}"))
                    if (state.portfolio?.ratesStale(state.fxNow) == true) Text("Устаревшая справочная котировка",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                else -> Text("Пересчёт ${account.currency} → ${state.baseCurrency} недоступен",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ConverterDialog(state: MoneyFlowUiState, viewModel: MoneyFlowViewModel, dismiss: () -> Unit) {
    var from by rememberSaveable { mutableStateOf(state.selectedAccount?.currency ?: state.baseCurrency) }
    var to by rememberSaveable { mutableStateOf(state.baseCurrency) }
    var amount by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { viewModel.clearConversion(); dismiss() },
        title = { Text("Конвертер валют") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Справочный пересчёт. Деньги не обмениваются, счета не изменяются.", style = MaterialTheme.typography.bodySmall)
                CurrencySelector("Из валюты", SupportedCurrency.fromCode(from), { from = it.code; viewModel.clearConversion() },
                    modifier = Modifier.fillMaxWidth().testTag("convert_from"), tagPrefix = "convert_from")
                CurrencySelector("В валюту", SupportedCurrency.fromCode(to), { to = it.code; viewModel.clearConversion() },
                    modifier = Modifier.fillMaxWidth().testTag("convert_to"), tagPrefix = "convert_to")
                OutlinedTextField(amount, { amount = it; viewModel.clearConversion() }, label = { Text("Сумма") },
                    suffix = { Text(from) }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    trailingIcon = { TextButton(onClick = {
                        val value = amount.trim()
                        amount = if (value.startsWith("-")) value.drop(1) else "-$value"
                        viewModel.clearConversion()
                    }, modifier = Modifier.testTag("convert_sign")) { Text("±") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("convert_amount"))
                state.conversionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.conversion?.let { result ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(Money.format(result.convertedAmountMinor, result.toCurrency),
                                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("convert_result"))
                            Text("1 ${result.fromCurrency} = ${result.rate} ${result.toCurrency}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            if (result.stale) StatusBadge("Устаревшая котировка", true)
                            if (result.fromCurrency == result.toCurrency) Text("Валюты совпадают. Внешний курс не используется.",
                                style = MaterialTheme.typography.bodySmall)
                            else {
                                Text("Frankfurter · дата котировки ${result.rateDate}", style = MaterialTheme.typography.labelSmall)
                                result.fetchedAt?.let { Text("Курсы получены: ${fxTimestamp(it)}", style = MaterialTheme.typography.labelSmall) }
                            }
                            Text("Результат округлён до минимальной единицы валюты.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.convert(amount, SupportedCurrency.fromCode(from), SupportedCurrency.fromCode(to)) },
                enabled = !state.conversionWorking, modifier = Modifier.testTag("convert_submit")) {
                Text(if (state.conversionWorking) "Считаем…" else "Рассчитать")
            }
        },
        dismissButton = { TextButton(onClick = { viewModel.clearConversion(); dismiss() }) { Text("Закрыть") } },
    )
}

@Composable
private fun StatusBadge(text: String, warning: Boolean) {
    Surface(color = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (warning) Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun fxTimestamp(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru-RU"))
        .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
