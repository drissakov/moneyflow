package com.moneyflow.app.ui

import com.moneyflow.app.data.domain.Category
import java.util.Locale

internal data class CategoryPickerGroup(val root: Category, val children: List<Category>)

/** A root match includes all its children; a child match retains its root as context. */
internal fun categoryPickerGroups(
    categories: List<Category>,
    kind: String,
    rootsOnly: Boolean,
    query: String,
): List<CategoryPickerGroup> {
    val eligible = categories.filter { it.kind == kind }
    val childrenByParent = if (kind == "expense" && !rootsOnly) eligible.filter { it.parentId != null }.groupBy { it.parentId }
        else emptyMap()
    val search = query.trim().lowercase(Locale.ROOT)
    return eligible.filter { it.parentId == null }.mapNotNull { root ->
        val children = childrenByParent[root.id].orEmpty()
        if (search.isEmpty() || root.name.lowercase(Locale.ROOT).contains(search)) CategoryPickerGroup(root, children)
        else children.filter { it.name.lowercase(Locale.ROOT).contains(search) }
            .takeIf { it.isNotEmpty() }?.let { CategoryPickerGroup(root, it) }
    }
}
