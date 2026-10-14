package com.moneyflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.Category
import com.moneyflow.app.data.domain.MoneyTransaction

internal fun categoryPath(categoryId: String?, categories: List<Category>): String {
    val category = categories.firstOrNull { it.id == categoryId } ?: return "Без категории"
    val parent = categories.firstOrNull { it.id == category.parentId }
    return if (parent == null) category.name else "${parent.name} › ${category.name}"
}

@Composable
internal fun CategorySelector(
    categories: List<Category>,
    kind: String,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onCreate: (() -> Unit)? = null,
    enabled: Boolean = true,
    label: String = "Категория",
    tagPrefix: String = "transaction_category",
    rootsOnly: Boolean = false,
) {
    // Transient picker state belongs to this field, never to a saved transaction or session.
    var expanded by remember(kind, tagPrefix, rootsOnly) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tagPrefix), shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.padding(end = 12.dp)) {
                Icon(NavigationIcons.Categories, contentDescription = null, modifier = Modifier.padding(11.dp).size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (rootsOnly && selectedId == null) "Выберите категорию" else categoryPath(selectedId, categories),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
            }
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (expanded && enabled) CategoryPickerSheet(
        categories = categories, kind = kind, selectedId = selectedId, rootsOnly = rootsOnly,
        label = label, tagPrefix = tagPrefix, onDismiss = { expanded = false },
        onSelect = { expanded = false; onSelect(it) },
        onCreate = onCreate?.let { create -> { expanded = false; create() } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoriesScreen(state: MoneyFlowUiState, viewModel: MoneyFlowViewModel) {
    var kind by rememberSaveable { mutableStateOf("expense") }
    var creating by rememberSaveable { mutableStateOf(false) }
    var parentId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshCategories() }
    val roots = state.categories.filter { it.kind == kind && it.parentId == null }
    LazyColumn(
        Modifier.widthIn(max = 620.dp).fillMaxSize().testTag("categories_screen"),
        contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Категории", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Сгруппируйте операции так, как удобно вам.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == "expense", onClick = { kind = "expense" }, label = { Text("Расходы") }, modifier = Modifier.testTag("categories_expense"))
                    FilterChip(kind == "income", onClick = { kind = "income" }, label = { Text("Доходы") }, modifier = Modifier.testTag("categories_income"))
                }
                Button(onClick = { parentId = null; viewModel.clearCategoryMessages(); creating = true },
                    enabled = !state.categoryWorking, shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxWidth().testTag("category_add")) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Новая категория")
                }
                if (kind == "expense") Text("У расходов есть категории и подкатегории. Например: «Продукты» → «Кофе».",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text("Категории доходов без подкатегорий.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.categoriesLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.categoryError?.let { error -> item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::refreshCategories, enabled = !state.categoriesLoading) { Text("Повторить загрузку") }
            }
        } }
        if (roots.isEmpty() && !state.categoriesLoading && state.categoryError == null) item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Text("Категорий пока нет. Добавьте первую.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp))
            }
        }
        items(roots, key = { it.id }) { category ->
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().testTag("category_card_${category.id}")) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(category.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (category.isDefault) "Стандартная категория" else "Ваша категория", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val children = state.categories.filter { it.parentId == category.id }
                    children.forEach { child ->
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("↳", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(child.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (kind == "expense") TextButton(
                        onClick = { parentId = category.id; viewModel.clearCategoryMessages(); creating = true },
                        enabled = !state.categoryWorking, modifier = Modifier.testTag("category_sub_add_${category.id}"),
                    ) { Text("+ Подкатегория") }
                }
            }
        }
    }
    if (creating) CreateCategoryDialog(state, viewModel, kind, parentId, dismiss = { creating = false })
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun CreateCategoryDialog(
    state: MoneyFlowUiState,
    viewModel: MoneyFlowViewModel,
    kind: String,
    initialParentId: String? = null,
    dismiss: () -> Unit,
    onCreated: (String) -> Unit = {},
) {
    var name by rememberSaveable { mutableStateOf("") }
    var subcategory by rememberSaveable { mutableStateOf(kind == "expense" && initialParentId != null) }
    var parentId by rememberSaveable { mutableStateOf(initialParentId) }
    val initialVersion = remember { state.categoryMutationVersion }
    LaunchedEffect(state.categoryMutationVersion) {
        if (state.categoryMutationVersion != initialVersion) {
            state.lastCreatedCategoryId?.let(onCreated)
            dismiss()
        }
    }
    AlertDialog(
        onDismissRequest = { if (!state.categoryWorking) dismiss() },
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text(if (subcategory) "Новая подкатегория" else "Новая категория ${if (kind == "income") "доходов" else "расходов"}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (kind == "expense") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!subcategory, onClick = { subcategory = false }, label = { Text("Категория") },
                            enabled = !state.categoryWorking, modifier = Modifier.testTag("category_level_root"))
                        FilterChip(subcategory, onClick = { subcategory = true }, label = { Text("Подкатегория") },
                            enabled = !state.categoryWorking, modifier = Modifier.testTag("category_level_child"))
                    }
                    if (subcategory) CategorySelector(state.categories, kind, parentId, { parentId = it },
                        enabled = !state.categoryWorking, label = "Родительская категория", tagPrefix = "category_parent", rootsOnly = true)
                }
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    supportingText = { Text(if (subcategory) "Например: «Кофе» или «Сигареты»" else if (kind == "income") "Например: «Продажа автомобиля»" else "Например: «Покупка автомобиля»") },
                    enabled = !state.categoryWorking, modifier = Modifier.fillMaxWidth().testTag("category_name"))
                state.categoryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.createCategory(kind, name, if (subcategory) parentId else null) },
                enabled = !state.categoryWorking && name.trim().isNotEmpty() && (!subcategory || parentId != null),
                modifier = Modifier.testTag("category_submit")) {
                if (state.categoryWorking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Создать")
            }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.categoryWorking) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AssignCategoryDialog(
    transaction: MoneyTransaction,
    state: MoneyFlowUiState,
    viewModel: MoneyFlowViewModel,
    dismiss: () -> Unit,
) {
    var categoryId by rememberSaveable(transaction.id) { mutableStateOf(transaction.categoryId) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val initialVersion = remember { state.transactionCategoryVersion }
    LaunchedEffect(state.transactionCategoryVersion) { if (state.transactionCategoryVersion != initialVersion) dismiss() }
    AlertDialog(
        onDismissRequest = { if (!state.working && !state.categoryWorking) dismiss() },
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text("Категория операции") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(transaction.note.ifBlank { if (transaction.kind == "income") "Доход" else "Расход" }, style = MaterialTheme.typography.bodyMedium)
                CategorySelector(state.categories, transaction.kind, categoryId, { categoryId = it },
                    onCreate = { viewModel.clearCategoryMessages(); creating = true }, enabled = !state.working && !state.categoryWorking,
                    tagPrefix = "assign_category")
                if (state.categoriesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!creating) state.categoryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.setTransactionCategory(transaction.id, categoryId) }, enabled = !state.working && !state.categoryWorking,
                modifier = Modifier.testTag("assign_category_submit")) { Text(if (state.working) "Сохраняем…" else "Сохранить") }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.working && !state.categoryWorking) { Text("Отмена") } },
    )
    if (creating) CreateCategoryDialog(state, viewModel, transaction.kind, dismiss = { creating = false }, onCreated = { categoryId = it })
}
