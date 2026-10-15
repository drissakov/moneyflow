package com.moneyflow.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.SupportedCurrency
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val P0Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 8.dp, bottomEnd = 28.dp, bottomStart = 8.dp)

/** Configuration recreation preserves drafts; an actual account switch clears them. */
@Composable
internal fun P0ResetOnUserChange(userId: String?, onReset: () -> Unit) {
    var previousUserId by rememberSaveable { mutableStateOf(userId) }
    val currentReset by rememberUpdatedState(onReset)
    LaunchedEffect(userId) {
        if (previousUserId != userId) { currentReset(); previousUserId = userId }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun P0Sheet(tag: String, title: String, eyebrow: String, busy: Boolean, onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    val currentBusy by rememberUpdatedState(busy)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !currentBusy || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheet,
        dragHandle = null, containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 8.dp), tonalElevation = 0.dp,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(tag)) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .92f)
            .imePadding().verticalScroll(rememberScrollState()).testTag("${tag}_content").padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() })
                }
                IconButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag("${tag}_close")) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
internal fun P0Heading(index: String, title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("$index / MONEYFLOW", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun P0Message(text: String, onDismiss: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (onDismiss != null) IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Закрыть сообщение") }
        }
    }
}

@Composable
internal fun P0Choice(title: String, value: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            Text("↗", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

internal data class P0Option(val id: String, val title: String, val subtitle: String = "")

@Composable
internal fun P0OptionsSheet(title: String, options: List<P0Option>, selectedId: String?, tag: String,
    onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val visible = remember(options, query) {
        options.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || it.subtitle.contains(query.trim(), ignoreCase = true) }
    }
    P0Sheet(tag, title, "Выбор", false, onDismiss) {
        if (options.size > 8) OutlinedTextField(query, { query = it.take(100) }, singleLine = true,
            placeholder = { Text("Поиск") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            shape = P0Shape, modifier = Modifier.fillMaxWidth().testTag("${tag}_search"))
        if (visible.isEmpty()) Text(if (options.isEmpty()) "Нет доступных вариантов" else "Ничего не найдено. Попробуйте другое название.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        visible.forEach { option ->
            Surface(shape = RoundedCornerShape(16.dp),
                color = if (selectedId == option.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = if (selectedId == option.id) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onSelect(option.id) }.testTag("${tag}_${option.id}")) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(option.title, style = MaterialTheme.typography.titleMedium)
                        if (option.subtitle.isNotBlank()) Text(option.subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                    if (selectedId == option.id) Text("✓", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
internal fun P0Submit(text: String, tag: String, busy: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, shape = P0Shape,
        contentPadding = PaddingValues(18.dp), modifier = Modifier.fillMaxWidth().testTag(tag)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun P0Confirmation(title: String, explanation: String, action: String, tag: String,
    state: LedgerUiState, viewModel: LedgerViewModel, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val revision = rememberSaveable(tag) { state.revision }
    LaunchedEffect(state.revision) { if (state.revision != revision) onDismiss() }
    P0Sheet(tag, title, "Подтверждение", state.busy, onDismiss) {
        Text(explanation, style = MaterialTheme.typography.bodyLarge)
        P0FormStatus(state, viewModel)
        P0Submit(action, "${tag}_confirm", state.busy, state.canWrite, onConfirm)
        OutlinedButton(onClick = onDismiss, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
    }
}

@Composable
internal fun P0FormStatus(state: LedgerUiState, viewModel: LedgerViewModel) {
    state.error?.let { P0Message(it) }
    if (state.recoveryUnavailable) {
        P0Message("Не удалось прочитать защищённое подтверждение. Новые изменения временно недоступны; исходное действие сохранено.")
        OutlinedButton(onClick = viewModel::refresh, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("ledger_form_retry_storage")) { Text("Повторить проверку") }
    } else if ((state.pending != null || state.manualPending) && !state.busy) {
        P0Message("Действие сохранено, но ответ сервера не получен. Проверьте результат — повтор не создаст дубль.")
        OutlinedButton(onClick = viewModel::retryPending, enabled = !state.externalBlocked && !state.recoveryChecking,
            modifier = Modifier.fillMaxWidth().testTag("ledger_form_retry_pending")) { Text("Проверить результат") }
    }
}

@Composable
internal fun P0DateTimeFields(date: String, time: String, onDate: (String) -> Unit, onTime: (String) -> Unit,
    enabled: Boolean, tag: String) {
    val context = LocalContext.current
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
    val parsedTime = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.now())
    P0Choice("Дата операции", parsedDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), "${tag}_date", enabled) {
        DatePickerDialog(context, { _, year, month, day -> onDate(LocalDate.of(year, month + 1, day).toString()) },
            parsedDate.year, parsedDate.monthValue - 1, parsedDate.dayOfMonth).show()
    }
    P0Choice("Время", parsedTime.format(DateTimeFormatter.ofPattern("HH:mm")), "${tag}_time", enabled) {
        TimePickerDialog(context, { _, hour, minute -> onTime(LocalTime.of(hour, minute).toString()) },
            parsedTime.hour, parsedTime.minute, true).show()
    }
}

internal fun p0Input(minor: Long, currency: String): String = BigDecimal.valueOf(minor)
    .movePointLeft(SupportedCurrency.fromCode(currency).scale).toPlainString()

internal fun p0LocalDate(instant: String): String = runCatching {
    Instant.parse(instant).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}.getOrDefault(LocalDate.now().toString())

internal fun p0LocalTime(instant: String): String = runCatching {
    Instant.parse(instant).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))

internal fun p0Instant(date: String, time: String): String = LocalDate.parse(date).atTime(LocalTime.parse(time))
    .atZone(ZoneId.systemDefault()).toInstant().toString()

internal fun p0Money(minor: Long, code: String): String = runCatching { Money.format(minor, code) }.getOrDefault("$minor $code")
