package com.moneyflow.app.ui

import com.moneyflow.app.data.domain.Category
import com.moneyflow.app.data.remote.ImportPreviewRow

internal fun activeImportCategory(categories: List<Category>, id: String?, kind: String): Boolean {
    val category = categories.firstOrNull { it.id == id && it.kind == kind && !it.archived } ?: return false
    return category.parentId == null || categories.any {
        it.id == category.parentId && it.kind == kind && it.parentId == null && !it.archived
    }
}

internal fun initialImportChoices(rows: List<ImportPreviewRow>, categories: List<Category>): Map<Int, ImportRowChoice> =
    rows.associate { row ->
        row.rowId to ImportRowChoice(
            selected = row.duplicate == "new",
            categoryId = row.suggestedCategoryId?.takeIf { activeImportCategory(categories, it, row.kind) },
        )
    }

/** A match key groups descriptions only. It never selects a row or confirms a possible duplicate/transfer. */
internal fun similarImportCategoryRows(
    rows: List<ImportPreviewRow>, choices: Map<Int, ImportRowChoice>, categories: List<Category>, sourceId: Int,
): List<Int> {
    val source = rows.firstOrNull { it.rowId == sourceId && it.duplicate != "exact" } ?: return emptyList()
    val choice = choices[sourceId] ?: return emptyList()
    val key = source.categoryMatchKey?.takeIf { it.matches(Regex("v1:[0-9a-f]{64}")) } ?: return emptyList()
    if (!choice.selected || choice.excludeFromAnalytics || !choice.categoryEdited ||
        !activeImportCategory(categories, choice.categoryId, source.kind)) return emptyList()
    return rows.filter { row ->
        val target = choices[row.rowId]
        row.rowId != sourceId && row.kind == source.kind && row.categoryMatchKey == key && row.duplicate != "exact" &&
            target?.selected == true && !target.excludeFromAnalytics && !target.categoryEdited && target.categoryId != choice.categoryId
    }.map { it.rowId }
}

internal fun applyImportCategoryToSimilar(
    rows: List<ImportPreviewRow>, choices: Map<Int, ImportRowChoice>, categories: List<Category>, sourceId: Int,
): Map<Int, ImportRowChoice> {
    val targets = similarImportCategoryRows(rows, choices, categories, sourceId).toSet()
    if (targets.isEmpty()) return choices
    val categoryId = choices.getValue(sourceId).categoryId
    return choices.mapValues { (id, choice) ->
        if (id in targets) choice.copy(categoryId = categoryId, categoryEdited = true) else choice
    }
}
