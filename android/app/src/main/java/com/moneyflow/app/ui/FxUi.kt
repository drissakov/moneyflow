package com.moneyflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.SupportedCurrency
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text("${selected.code} · ${selected.title}", maxLines = 2, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Выбрать валюту")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 350.dp)) {
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

@Composable
internal fun PortfolioCard(state: MoneyFlowUiState, viewModel: MoneyFlowViewModel, openConverter: () -> Unit) {
    val portfolio = state.portfolio
    val stale = portfolio?.ratesStale(state.fxNow) == true
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Весь капитал", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (state.fxRefreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            CurrencySelector("Основная валюта", SupportedCurrency.fromCode(state.baseCurrency),
                { viewModel.setBaseCurrency(it.code) }, modifier = Modifier.fillMaxWidth().testTag("base_currency"),
                enabled = !state.working, tagPrefix = "base")
            if (state.basePreferencePending) StatusBadge("Выбор сохранён на устройстве · ожидает синхронизации", false)
            when {
                portfolio?.complete == true -> {
                    Text(Money.format(requireNotNull(portfolio.totalBalanceMinor), state.baseCurrency),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("portfolio_total"))
                }
                portfolio != null -> {
                    StatusBadge("Неполная оценка", true)
                    Text("Учтённая часть: ${Money.format(portfolio.partialBalanceMinor, state.baseCurrency)}",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("portfolio_partial"))
                    Text("Без курсов: ${portfolio.missingCurrencies.joinToString()}. Общий итог недоступен.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text("Оценка недоступна", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("portfolio_unavailable"))
                    Text(when {
                        !state.accountsLoaded -> "Загружаем счета…"
                        state.cachedPortfolio?.portfolio?.ratesExpired(state.fxNow) == true -> "Сохранённые курсы старше 7 дней. Обновите оценку."
                        state.cachedPortfolio != null -> "Остатки изменились. Нужна оценка для актуальных счетов."
                        state.fxRefreshing -> "Запрашиваем справочные курсы Frankfurter…"
                        else -> "Обновите курсы. Остатки счетов доступны в исходных валютах."
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (portfolio != null) {
                if (stale) StatusBadge("Устаревшие курсы · оценка приблизительная", true)
                else if (state.lastFxConfirmedAt == null) StatusBadge("Сохранённая оценка из кэша", false)
                val dates = portfolio.accounts.mapNotNull { it.rateDate }.distinct().sorted()
                Text(if (dates.isEmpty() && !portfolio.complete) "Frankfurter · доступных внешних котировок нет" else
                    if (dates.isEmpty()) "Пересчёт в своей валюте без внешней котировки" else
                    "Frankfurter · даты котировок: ${dates.joinToString()}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                portfolio.fetchedAt?.let { Text("Курсы получены: ${fxTimestamp(it)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            state.fxError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("fx_error")) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Справочная оценка. Остатки счетов сохраняются в исходных валютах.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::refreshFx, enabled = !state.fxRefreshing,
                    modifier = Modifier.testTag("refresh_fx")) { Text("Обновить курсы") }
                TextButton(onClick = openConverter, modifier = Modifier.testTag("open_converter")) { Text("Конвертер") }
            }
        }
    }
}

@Composable
internal fun FxAccountCard(account: Account, state: MoneyFlowUiState, onSelect: () -> Unit) {
    val selected = state.selectedAccountId == account.id
    val estimate = state.portfolio?.accounts?.firstOrNull { it.id == account.id }
    Card(onClick = onSelect, shape = RoundedCornerShape(16.dp),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().testTag("account_${account.id}")) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(account.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                Spacer(Modifier.width(8.dp))
                Text(Money.format(account.balanceMinor, account.currency), fontWeight = FontWeight.Bold)
            }
            when {
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
                    suffix = { Text(from) }, singleLine = true,
                    trailingIcon = { TextButton(onClick = {
                        val value = amount.trim()
                        amount = if (value.startsWith("-")) value.drop(1) else "-$value"
                        viewModel.clearConversion()
                    }, modifier = Modifier.testTag("convert_sign")) { Text("±") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("convert_amount"))
                state.conversionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.conversion?.let { result ->
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
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
        shape = RoundedCornerShape(8.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

private fun fxTimestamp(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru-RU"))
        .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)
