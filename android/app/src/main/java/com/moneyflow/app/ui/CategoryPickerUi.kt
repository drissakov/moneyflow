package com.moneyflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.moneyflow.app.data.domain.Category
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun CategoryPickerSheet(
    categories: List<Category>,
    kind: String,
    selectedId: String?,
    rootsOnly: Boolean,
    label: String,
    tagPrefix: String,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
    onCreate: (() -> Unit)?,
) {
    var query by remember { mutableStateOf("") }
    var expandedRoots by remember {
        mutableStateOf(listOfNotNull(categories.firstOrNull { it.id == selectedId }?.parentId).toSet())
    }
    val groups = remember(categories, kind, rootsOnly, query) { categoryPickerGroups(categories, kind, rootsOnly, query) }
    val focus = LocalFocusManager.current
    val standard = groups.filter { it.root.isDefault }
    val custom = groups.filter { !it.root.isDefault }
    val searching = query.isNotBlank()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var dismissing by remember { mutableStateOf(false) }
    val dismissThen: (() -> Unit) -> Unit = { action ->
        if (!dismissing) {
            dismissing = true
            scope.launch {
                try {
                    sheetState.hide()
                    action()
                } finally {
                    dismissing = false
                }
            }
        }
    }
    val choose: (String?) -> Unit = { id -> dismissThen { onSelect(id) } }
    val sheetMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.90f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("${tagPrefix}_sheet"),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight).imePadding()) {
            // IME padding is applied before measuring this viewport. In short windows the
            // action joins the same scroll container, so chrome cannot consume the whole list.
            val compact = maxHeight < (360f * LocalDensity.current.fontScale).dp
            Column(Modifier.fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false).testTag("${tagPrefix}_list"),
                    contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "header") {
                        CategoryPickerHeader(label,
                            if (rootsOnly) "Выберите основную категорию" else if (kind == "income") "Откуда поступили деньги" else "На что потрачены деньги",
                            tagPrefix, onClose = { dismissThen(onDismiss) })
                    }
                    // The search keeps one stable item/composition as height and IME change.
                    item(key = "search") {
                        CategoryPickerSearch(query, { query = it }, tagPrefix, onSearch = { focus.clearFocus() })
                    }
                    if (compact && onCreate != null) item(key = "create") {
                        CategoryPickerCreateAction(tagPrefix, onCreate = { dismissThen(onCreate) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    }
                    item(key = "none") {
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            CategoryPickerChoice(
                                title = if (rootsOnly) "Не выбрано" else "Без категории",
                                subtitle = if (rootsOnly) "Выберите основную категорию ниже" else "Можно назначить позже",
                                selected = selectedId == null, tag = "${tagPrefix}_none", onClick = { choose(null) },
                            )
                        }
                    }
                    if (groups.isEmpty()) item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (searching) "Ничего не найдено" else "Пока нет категорий", style = MaterialTheme.typography.titleMedium)
                            Text(if (searching) {
                                if (onCreate == null) "Попробуйте другое название." else "Попробуйте другое название или создайте свою категорию."
                            } else if (onCreate != null) "Создайте категорию, чтобы сгруппировать операции." else "Категории появятся после загрузки списка.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    for ((title, section) in listOf("Стандартные" to standard, "Ваши категории" to custom)) {
                        if (section.isNotEmpty()) item(key = "heading_$title") {
                            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp).semantics { heading() })
                        }
                        items(section, key = { it.root.id }) { group ->
                            CategoryPickerGroupCard(
                                group = group, selectedId = selectedId,
                                expanded = searching || group.root.id in expandedRoots,
                                canCollapse = !searching, tagPrefix = tagPrefix, onSelect = { choose(it) },
                                onToggle = {
                                    expandedRoots = if (group.root.id in expandedRoots) expandedRoots - group.root.id
                                        else expandedRoots + group.root.id
                                },
                            )
                        }
                    }
                }
                if (!compact && onCreate != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CategoryPickerCreateAction(tagPrefix, onCreate = { dismissThen(onCreate) }, modifier = Modifier.fillMaxWidth().padding(20.dp))
                }
            }
        }
    }
}

@Composable
private fun CategoryPickerHeader(label: String, subtitle: String, tagPrefix: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("${tagPrefix}_close")) {
            Icon(Icons.Default.Close, contentDescription = "Закрыть выбор категории")
        }
    }
}

@Composable
private fun CategoryPickerSearch(query: String, onQuery: (String) -> Unit, tagPrefix: String, onSearch: () -> Unit) {
    OutlinedTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text("Найти категорию") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) { {
            IconButton(onClick = { onQuery("") }, modifier = Modifier.testTag("${tagPrefix}_search_clear")) {
                Icon(Icons.Default.Close, contentDescription = "Очистить поиск")
            }
        } } else null,
        shape = RoundedCornerShape(18.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_search"),
    )
}

@Composable
private fun CategoryPickerCreateAction(tagPrefix: String, onCreate: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onCreate,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        modifier = modifier.testTag("${tagPrefix}_create"),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 10.dp).size(20.dp))
        Text("Создать свою категорию", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CategoryPickerGroupCard(
    group: CategoryPickerGroup,
    selectedId: String?,
    expanded: Boolean,
    canCollapse: Boolean,
    tagPrefix: String,
    onSelect: (String) -> Unit,
    onToggle: () -> Unit,
) {
    val hasChildren = group.children.isNotEmpty()
    val selectedRoot = selectedId == group.root.id
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryPickerChoice(
                    title = group.root.name,
                    subtitle = if (hasChildren) "${group.children.size} ${subcategoryCountLabel(group.children.size)}" else null,
                    selected = selectedRoot, tag = "${tagPrefix}_${group.root.id}",
                    onClick = { onSelect(group.root.id) }, modifier = Modifier.weight(1f),
                )
                if (hasChildren && canCollapse) IconButton(
                    onClick = onToggle, modifier = Modifier.padding(end = 4.dp).testTag("${tagPrefix}_expand_${group.root.id}"),
                ) {
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "${if (expanded) "Свернуть" else "Показать"} подкатегории: ${group.root.name}")
                }
            }
            if (hasChildren && expanded) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp)) {
                    group.children.forEach { child ->
                        CategoryPickerChoice(title = child.name, selected = child.id == selectedId,
                            tag = "${tagPrefix}_${child.id}", onClick = { onSelect(child.id) }, child = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPickerChoice(
    title: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    child: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
            .clickable(role = Role.RadioButton, onClickLabel = "Выбрать $title", onClick = onClick)
            .semantics { this.selected = selected }.testTag(tag).heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!child) Surface(
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(NavigationIcons.Categories, contentDescription = null, modifier = Modifier.padding(10.dp).size(20.dp))
        } else Box(Modifier.size(8.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.outline, modifier = Modifier.size(5.dp).align(Alignment.Center)) {}
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

private fun subcategoryCountLabel(count: Int): String = when {
    count % 100 in 11..14 -> "подкатегорий"
    count % 10 == 1 -> "подкатегория"
    count % 10 in 2..4 -> "подкатегории"
    else -> "подкатегорий"
}
