package com.termux.app.ui.sessions

/**
 * Horizontal bounds of one tab in the sessions bar, in pixels, taken from a single layout pass.
 *
 * @property index Position of the tab in the list it was measured from.
 * @property startPx Left edge of the tab, inclusive.
 * @property endPx Right edge of the tab, exclusive.
 */
data class TabBounds(
    val index: Int,
    val startPx: Int,
    val endPx: Int,
)

/**
 * Returns the index the dragged tab should occupy when its horizontal centre is at
 * [draggedCenterPx], given the tabs' bounds from a single layout pass.
 *
 * The centre is compared against the bounds as laid out before the drag, so a tab changes place
 * once its centre has crossed the boundary between two tabs; dragging past either end parks it at
 * that end. An empty bar maps to index 0.
 */
fun dropIndex(tabs: List<TabBounds>, draggedCenterPx: Float): Int {
    for (tab in tabs) {
        if (draggedCenterPx < tab.endPx) return tab.index
    }
    return tabs.lastOrNull()?.index ?: 0
}

/**
 * Moves the element at [fromIndex] to [toIndex] within [list], in place, shifting the elements in
 * between. [toIndex] is clamped to the list's bounds; an empty list or an out-of-range [fromIndex]
 * leaves the list untouched. Returns true when the list changed.
 */
fun <T> moveItemInPlace(list: MutableList<T>, fromIndex: Int, toIndex: Int): Boolean {
    if (fromIndex !in list.indices) return false

    val target = toIndex.coerceIn(0, list.lastIndex)
    if (fromIndex == target) return false

    list.add(target, list.removeAt(fromIndex))
    return true
}
