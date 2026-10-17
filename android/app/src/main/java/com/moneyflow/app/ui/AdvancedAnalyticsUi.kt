package com.moneyflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.*
import com.moneyflow.app.data.remote.LedgerFilter
import java.math.BigInteger
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedAnalyticsScreen(state: AdvancedAnalyticsUiState, viewModel: AdvancedAnalyticsViewModel,
    onOpenHistory: (LedgerFilter) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("comparison") }
    var kind by rememberSaveable { mutableStateOf("expense") }
    var choosingAccount by rememberSaveable { mutableStateOf(false) }
    var selectedSeriesMonth by rememberSaveable(state.month, state.currency, state.months, state.accountId, state.mode) { mutableStateOf(state.month) }
    var selectedRootId by rememberSaveable(state.month, state.currency, state.accountId, state.mode, kind) { mutableStateOf<String?>(null) }
    var expandedTable by rememberSaveable { mutableStateOf(false) }
    P0ResetOnUserChange(state.session?.user?.id) { selectedRootId = null; choosingAccount = false; tab = "comparison" }
    LaunchedEffect(Unit) { viewModel.refresh() }
    val consolidated = state.mode == "consolidated"
    val report = state.report?.takeIf { it.month == state.month && it.currency == state.currency && it.months == state.months && it.accountId == state.accountId && it.mode == state.mode }
    val month = runCatching { YearMonth.parse(state.month) }.getOrDefault(YearMonth.now())
    val point = report?.series?.firstOrNull { it.month == selectedSeriesMonth } ?: report?.series?.lastOrNull()
    val roots = (if (kind == "income") report?.income else report?.expenses).orEmpty()
        .sortedWith(compareByDescending<AdvancedAnalyticsCategory> { it.amountMinor }.thenByDescending { it.previousAmountMinor }.thenBy { it.name })
    val selectedRoot = roots.firstOrNull { it.categoryId != null && it.categoryId == selectedRootId }
    val rows = selectedRoot?.let(::advancedCategoryChildren) ?: roots
    val currentTotal = selectedRoot?.amountMinor ?: if (kind == "income") report?.summary?.incomeMinor ?: 0L else report?.summary?.expenseMinor ?: 0L
    val openCategory: (AdvancedAnalyticsCategory, Boolean) -> Unit = { category, exact ->
        if (report != null) onOpenHistory(analyticsHistoryFilter(state, kind, report.from, report.to,
            category.categoryId, category.categoryId == null, exact))
    }
    val selectCategory: (AdvancedAnalyticsCategory) -> Unit = { category ->
        if (selectedRoot == null && category.categoryId != null && category.children.isNotEmpty()) selectedRootId = category.categoryId
        else openCategory(category, selectedRoot != null && category.categoryId == selectedRoot.categoryId)
    }
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize().testTag("reports_screen"), contentPadding = PaddingValues(20.dp, 14.dp, 20.dp, 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { P0Heading("04", "Жизнь.\nВ цифрах.", "Замечайте привычки, сравнивайте месяцы и разбирайтесь в деталях.") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("comparison" to "Сравнение", "balance" to "Баланс", "categories" to "Категории").forEach { (id, title) ->
                    FilterChip(tab == id, { tab = id }, label = { Text(title) }, modifier = Modifier.testTag("reports_tab_$id"))
                }
            }
        }
        item { ReportCurrencyMode(state.mode, state.loading) { choosingAccount = false; viewModel.setMode(it) } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReportMonthNavigation(month, state.loading, viewModel::setMonth)
                if (consolidated) {
                    Text("Все счета · KZT", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("reports_consolidated_scope"))
                    Text("Все валюты, включая архивные счета и скрытые из общего баланса. Доходы, расходы и остатки всех месяцев пересчитаны по одному текущему набору курсов.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    CurrencySelector("Валюта операций", SupportedCurrency.fromCode(state.currency), { viewModel.setCurrency(it.code) },
                        modifier = Modifier.fillMaxWidth().testTag("reports_currency"), tagPrefix = "reports_currency")
                    val selected = state.accounts.firstOrNull { it.id == state.accountId }
                    P0Choice("Какие счета учитывать", selected?.let { "${it.name}${if (it.archived) " · архив" else ""}" } ?: if (state.accountId == null) "Все счета в ${state.currency}" else "Выбранный счёт",
                        "reports_account", !state.loading) { choosingAccount = true }
                    Text("Без конвертации валют. В «Все счета» входят также архивные и скрытые из общего баланса.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Календарные месяцы по Казахстану · UTC+5.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tab != "categories") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 6, 12).forEach { span -> FilterChip(state.months == span, { viewModel.setMonths(span) }, label = { Text("$span мес.") },
                        enabled = !state.loading, modifier = Modifier.testTag("reports_span_$span")) }
                }
            }
        }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("reports_loading")) }
        state.error?.let { error -> item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                P0Message(error, viewModel::clearMessage)
                OutlinedButton(onClick = viewModel::refresh, enabled = !state.loading, modifier = Modifier.testTag("reports_retry")) { Text("Повторить загрузку") }
            }
        } }
        if (state.offline) item { P0Message("Сохранённый отчёт${state.receivedAt?.let { " от ${analyticsTimestamp(it.toString())}" }.orEmpty()}. Новые изменения могут быть не учтены; обновите данные при подключении.") }
        if (report != null) {
            report.conversion?.takeIf { consolidated }?.let { conversion -> item { ReportsConversionContext(conversion) } }
            if (report.periodStatus != "past") item { P0Message(if (report.periodStatus == "current")
                "Месяц ещё идёт. Итоги предварительные и включают все внесённые операции этого месяца, в том числе с будущей датой. Предыдущий месяц показан целиком."
                else "Будущий месяц: показаны только уже внесённые операции. Плановые платежи без подтверждения сюда не входят.") }
            when (tab) {
                "comparison" -> {
                    item { ReportsComparisonSummary(report) }
                    item { ReportChartCard("01 / ПО МЕСЯЦАМ", "Доходы рядом\nс расходами.") {
                        MonthlyComparisonBars(report.series, report.currency, point?.month ?: report.month) { selectedSeriesMonth = it }
                    } }
                    if (point != null) item { ReportMonthDetails(point, report.currency, false,
                        onCategories = { selectedRootId = null; tab = "categories"; viewModel.setMonth(point.month) },
                        onHistory = { type -> onOpenHistory(analyticsHistoryFilter(state, type, point.from, point.to)) }) }
                    item { Text("Разница доходов и расходов показывает, сколько удалось сохранить за месяц. Переводы между своими счетами и исключённые из отчётов операции здесь не учитываются; комиссии остаются расходами.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                "balance" -> {
                    item { ReportChartCard("02 / ТРАЕКТОРИЯ ДЕНЕГ", "Остаток меняется.\nИстория остаётся.") {
                        MonthlyBalanceLine(report.series, report.currency, point?.month ?: report.month) { selectedSeriesMonth = it }
                    } }
                    if (point != null) item { ReportMonthDetails(point, report.currency, true,
                        onCategories = { selectedRootId = null; tab = "categories"; viewModel.setMonth(point.month) },
                        onHistory = { type -> onOpenHistory(analyticsHistoryFilter(state, type, point.from, point.to)) }) }
                    item { P0Message("Остатки восстановлены из текущих начальных остатков счетов и всей сохранённой истории. Здесь учитываются переводы и движения, исключённые из отчётов. Поэтому изменение остатка может отличаться от разницы доходов и расходов.") }
                    item { Text(if (consolidated) "Все остатки выражены в тенге по одному текущему набору курсов. Это не история ежедневной валютной переоценки. Обновление курсов, изменение начального остатка или прошлой операции пересчитывает всю линию."
                        else "Баланс каждой валюты показан отдельно. Изменение начального остатка или прошлой операции пересчитывает линию; валютная переоценка не добавляется.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                "categories" -> {
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(kind == "expense", { kind = "expense" }, label = { Text("Расходы") }, modifier = Modifier.testTag("reports_categories_expense"))
                            FilterChip(kind == "income", { kind = "income" }, label = { Text("Доходы") }, modifier = Modifier.testTag("reports_categories_income"))
                        }
                    }
                    if (selectedRoot != null) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { selectedRootId = null }, modifier = Modifier.testTag("reports_categories_back")) { Text("← Все категории") }
                            Text(selectedRoot.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                            OutlinedButton(onClick = { openCategory(selectedRoot, false) }, shape = P0Shape,
                                modifier = Modifier.fillMaxWidth().testTag("reports_category_all_operations")) { Text("Все операции категории ↗") }
                            Text("«Без подкатегории» — операции, записанные напрямую в основную категорию.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item { ReportChartCard("03 / СТРУКТУРА МЕСЯЦА", if (kind == "income") "Откуда приходят деньги." else "На что уходят деньги.") {
                        if (currentTotal > 0) AdvancedCategoryRing(rows.filter { it.amountMinor > 0 }, currentTotal, report.currency,
                            if (kind == "income") "Доходы" else "Расходы", selectCategory)
                        else Text(if (rows.any { it.previousAmountMinor > 0 }) "В этом месяце — 0. Ниже показаны категории, которые были в прошлом месяце."
                            else "За выбранный месяц нет ${if (kind == "income") "доходов" else "расходов"}. Попробуйте другой месяц, счёт или валюту.",
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("reports_categories_empty"))
                    } }
                    items(rows, key = { "${it.categoryId}:${it.name}" }) { category ->
                        AdvancedCategoryRow(category, rows.indexOf(category) + 1, currentTotal, report.currency,
                            canDrill = selectedRoot == null && category.categoryId != null && category.children.isNotEmpty(),
                            onClick = { selectCategory(category) },
                            onPrevious = if (category.amountMinor == 0L && category.previousAmountMinor > 0) ({
                                val previous = YearMonth.parse(report.month).minusMonths(1)
                                val (from, to) = analyticsMonthBounds(previous)
                                onOpenHistory(analyticsHistoryFilter(state, kind, from, to, category.categoryId, category.categoryId == null,
                                    selectedRoot != null && category.categoryId == selectedRoot.categoryId))
                            }) else null)
                    }
                    if (selectedRoot == null) item {
                        OutlinedButton(onClick = { onOpenHistory(analyticsHistoryFilter(state, kind, report.from, report.to)) }, shape = P0Shape,
                            modifier = Modifier.fillMaxWidth().testTag("reports_categories_all_operations")) { Text("Все ${if (kind == "income") "доходы" else "расходы"} месяца ↗") }
                    }
                }
            }
            if (tab != "categories") {
                item { TextButton(onClick = { expandedTable = !expandedTable }, modifier = Modifier.testTag("reports_table_toggle")) {
                    Text(if (expandedTable) "Свернуть точные данные ↑" else "Точные данные по месяцам ↓")
                } }
                if (expandedTable) items(report.series, key = { "table_${it.month}" }) { row ->
                    Surface(shape = P0Shape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { selectedSeriesMonth = row.month }.testTag("reports_table_${row.month}")) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(analyticsMonthName(row.month, true), style = MaterialTheme.typography.titleLarge)
                            if (tab == "balance") {
                                PlanningReceiptLine("На начало", p0Money(row.openingBalanceMinor, report.currency))
                                PlanningReceiptLine("На конец", p0Money(row.closingBalanceMinor, report.currency), true)
                                PlanningReceiptLine("Изменение остатка", analyticsSignedMoney(row.ledgerChangeMinor, report.currency))
                            } else {
                                PlanningReceiptLine("Доходы", p0Money(row.incomeMinor, report.currency))
                                PlanningReceiptLine("Расходы", p0Money(row.expenseMinor, report.currency))
                                PlanningReceiptLine("Разница", analyticsSignedMoney(row.netMinor, report.currency), true)
                            }
                        }
                    }
                }
            }
            item {
                Text("Обновлено ${analyticsTimestamp(report.asOf)} · UTC+5", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = viewModel::refresh, enabled = !state.loading, modifier = Modifier.testTag("reports_refresh")) { Text("Обновить отчёт") }
            }
        } else if (!state.loading && state.error == null) item { P0Message("Выберите месяц и загрузите отчёт.") }
    }
    if (choosingAccount && !consolidated) P0OptionsSheet("Счёт для отчёта", listOf(P0Option("all", "Все счета в ${state.currency}", "Включая архивные и скрытые из общего баланса")) +
        state.accounts.filter { it.currency == state.currency }.map { P0Option(it.id, it.name, "${it.currency}${if (it.archived) " · архив" else ""}") },
        state.accountId ?: "all", "reports_accounts", { choosingAccount = false }) { viewModel.setAccount(it.takeUnless { id -> id == "all" }); choosingAccount = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportCurrencyMode(mode: String, loading: Boolean, onMode: (String) -> Unit) {
    Surface(shape = P0Shape, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("КАК СЧИТАТЬ ДЕНЬГИ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("original" to "В своей валюте", "consolidated" to "Всё в тенге").forEach { (id, label) ->
                    FilterChip(selected = mode == id, onClick = { onMode(id) }, enabled = !loading,
                        label = { Text(label) }, modifier = Modifier.testTag("reports_mode_$id"))
                }
            }
        }
    }
}

@Composable
private fun ReportsConversionContext(conversion: AdvancedAnalyticsConversion) {
    Surface(shape = P0Shape, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("reports_conversion_context")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("КУРСЫ · ${conversion.provider.uppercase()}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (conversion.stale) Text("Использованы сохранённые курсы. Они могут отличаться от последних доступных; попробуйте обновить отчёт.",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("reports_conversion_stale"))
            conversion.rates.sortedBy { it.currency }.forEach { rate ->
                Text(analyticsConversionRateText(rate), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("reports_conversion_rate_${rate.currency}"))
            }
            if (conversion.rates.none { it.currency != "KZT" }) Text("В этом отчёте нет валютных сумм для конвертации.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(conversion.fetchedAt?.let { "Курсы получены ${analyticsTimestamp(it)} · UTC+5" } ?: "Время получения курсов не указано.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Один набор курсов для всего периода. Исторические курсы по дням не применяются. Операции по нажатию открываются в исходных валютах.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReportMonthNavigation(month: YearMonth, loading: Boolean, onMonth: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("МЕСЯЦ ДЛЯ СРАВНЕНИЯ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onMonth(month.minusMonths(1).toString()) }, enabled = !loading && month > YearMonth.of(1901, 1), contentPadding = PaddingValues(12.dp),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Предыдущий месяц" }.testTag("reports_previous_month")) { Text("←") }
            Text(analyticsMonthName(month.toString(), true), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { onMonth(month.plusMonths(1).toString()) }, enabled = !loading && month < YearMonth.of(2199, 12), contentPadding = PaddingValues(12.dp),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Следующий месяц" }.testTag("reports_next_month")) { Text("→") }
        }
    }
}

@Composable
internal fun ReportChartCard(index: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = P0Shape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(index, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun ReportsComparisonSummary(report: AdvancedAnalyticsReport) {
    val summary = report.summary
    ReportChartCard("ИТОГ / ${analyticsMonthName(report.month, true).uppercase()}", if (summary.netMinor >= 0) "Осталось после трат." else "Расходы выше доходов.") {
        Text(analyticsSignedMoney(summary.netMinor, report.currency), style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.testTag("reports_net_total"))
        Text(if (report.mode == "consolidated") "Разница доходов и расходов · всё в тенге" else "Разница доходов и расходов",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ReportComparedMetric("Доходы", summary.incomeMinor, summary.previousIncomeMinor, summary.incomeDeltaMinor, report.currency, "reports_income_total")
        ReportComparedMetric("Расходы", summary.expenseMinor, summary.previousExpenseMinor, summary.expenseDeltaMinor, report.currency, "reports_expense_total")
        Text("В сравнении с ${analyticsMonthName(YearMonth.parse(report.month).minusMonths(1).toString(), true).lowercase()}. Знак изменения показывает рост или снижение суммы.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReportComparedMetric(title: String, current: Long, previous: Long, delta: Long, currency: String, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        PlanningReceiptLine(title, p0Money(current, currency), true, tag)
        Text("Ранее ${p0Money(previous, currency)} · изменение ${analyticsSignedMoney(delta, currency)}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportMonthDetails(point: AnalyticsMonthPoint, currency: String, balance: Boolean,
    onCategories: () -> Unit, onHistory: (String?) -> Unit) {
    ReportChartCard("ВЫБРАННЫЙ МЕСЯЦ", analyticsMonthName(point.month, true)) {
        if (balance) {
            PlanningReceiptLine("На начало месяца", p0Money(point.openingBalanceMinor, currency))
            PlanningReceiptLine("На конец месяца", p0Money(point.closingBalanceMinor, currency), true)
            PlanningReceiptLine("Изменение остатка", analyticsSignedMoney(point.ledgerChangeMinor, currency))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        PlanningReceiptLine("Доходы", p0Money(point.incomeMinor, currency))
        PlanningReceiptLine("Расходы", p0Money(point.expenseMinor, currency))
        PlanningReceiptLine("Доходы − расходы", analyticsSignedMoney(point.netMinor, currency), !balance)
        P0Submit("Категории этого месяца ↗", "reports_selected_categories", false, YearMonth.parse(point.month).year in 1901..2199, onCategories)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onHistory("income") }, modifier = Modifier.testTag("reports_selected_income")) { Text("Операции доходов ↗") }
            TextButton(onClick = { onHistory("expense") }, modifier = Modifier.testTag("reports_selected_expense")) { Text("Операции расходов ↗") }
        }
    }
}

internal fun analyticsSignedMoney(amount: Long, currency: String): String = (if (amount > 0) "+ " else "") + p0Money(amount, currency)

internal fun analyticsHistoryFilter(state: AdvancedAnalyticsUiState, kind: String?, from: String, to: String,
    categoryId: String? = null, uncategorized: Boolean = false, categoryExact: Boolean = false): LedgerFilter =
    LedgerFilter(accountId = state.accountId.takeUnless { state.mode == "consolidated" }, categoryId = categoryId, kind = kind, from = from, to = to,
        currency = state.currency.takeUnless { state.mode == "consolidated" },
        analyticsOnly = true, uncategorized = uncategorized, categoryExact = categoryExact)

internal fun analyticsConversionRateText(rate: AdvancedAnalyticsRate): String {
    val date = rate.rateDate?.let { value -> runCatching { java.time.LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) }.getOrDefault(value) }
    return "1 ${rate.currency} = ${rate.rate.stripTrailingZeros().toPlainString().replace('.', ',')} KZT" +
        (date?.let { " · $it" } ?: if (rate.currency == "KZT") " · базовая валюта" else " · дата курса не указана")
}

internal fun analyticsMonthBounds(month: YearMonth): Pair<String, String> =
    month.atDay(1).atStartOfDay().atOffset(ZoneOffset.ofHours(5)).toInstant().toString() to
        month.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.ofHours(5)).toInstant().toString()

internal fun advancedCategoryChildren(root: AdvancedAnalyticsCategory): List<AdvancedAnalyticsCategory> {
    val previousChildren = root.children.fold(BigInteger.ZERO) { sum, child -> sum + BigInteger.valueOf(child.previousAmountMinor) }
    val previousDirect = (BigInteger.valueOf(root.previousAmountMinor) - previousChildren).toString().toLong()
    return (root.children + if (root.directAmountMinor > 0 || previousDirect > 0) listOf(
        AdvancedAnalyticsCategory(root.categoryId, "Без подкатегории", root.directAmountMinor, root.directAmountMinor,
            previousDirect, Math.subtractExact(root.directAmountMinor, previousDirect)),
    ) else emptyList()).sortedWith(compareByDescending<AdvancedAnalyticsCategory> { it.amountMinor }.thenByDescending { it.previousAmountMinor }.thenBy { it.name })
}

private fun analyticsTimestamp(value: String): String = runCatching {
    Instant.parse(value).atOffset(ZoneOffset.ofHours(5)).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
}.getOrDefault(value)
