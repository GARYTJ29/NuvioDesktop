package com.nuvio.app.core.ui.find

import androidx.compose.ui.geometry.Rect
import com.nuvio.app.core.ui.FindableItems
import com.nuvio.app.core.ui.FindableItemsKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.toSize
import java.awt.Window
import javax.accessibility.AccessibleContext

/** Test tag on the find overlay, so the find bar never matches its own query text. */
internal const val FIND_IN_PAGE_TAG = "nuvio-find-in-page"

private const val MAX_ACCESSIBLE_DEPTH = 24

/**
 * Finds the unmerged semantics roots of [window]'s Compose content.
 *
 * Compose Desktop exposes its semantics through the window's Java accessibility tree; any
 * `ComposeAccessible` leads to its owner, whose unmerged root keeps each Text as its own node
 * (the merged tree would fold a card's texts into one box).
 */
internal fun findSemanticRoots(window: Window): List<SemanticsNode> {
    val roots = LinkedHashMap<Any, SemanticsNode>()

    fun visit(context: AccessibleContext?, depth: Int) {
        if (context == null || depth > MAX_ACCESSIBLE_DEPTH) return
        val count = runCatching { context.accessibleChildrenCount }.getOrDefault(0)
        for (index in 0 until count) {
            val child = runCatching { context.getAccessibleChild(index) }.getOrNull() ?: continue
            val node = composeSemanticsNodeOf(child)
            if (node != null) {
                val owner = runCatching { node.root?.semanticsOwner }.getOrNull()
                if (owner != null && owner !in roots) {
                    roots[owner] = owner.unmergedRootSemanticsNode
                }
            } else {
                visit(runCatching { child.accessibleContext }.getOrNull(), depth + 1)
            }
        }
    }

    visit(runCatching { window.accessibleContext }.getOrNull(), 0)
    return roots.values.toList()
}

private const val COMPOSE_ACCESSIBLE_CLASS = "androidx.compose.ui.platform.a11y.ComposeAccessible"

/**
 * `ComposeAccessible` is Kotlin-internal, but its `getSemanticsNode()` is a public JVM method.
 * If a Compose update renames it this returns null and find simply reports no matches.
 */
private val composeAccessibleSemanticsNode: java.lang.reflect.Method? by lazy {
    runCatching {
        Class.forName(COMPOSE_ACCESSIBLE_CLASS).getMethod("getSemanticsNode")
    }.getOrNull()
}

private fun composeSemanticsNodeOf(accessible: Any): SemanticsNode? {
    if (accessible.javaClass.name != COMPOSE_ACCESSIBLE_CLASS) return null
    val method = composeAccessibleSemanticsNode ?: return null
    return runCatching { method.invoke(accessible) as? SemanticsNode }.getOrNull()
}

/** Every placed text node under [roots] containing [query] (case-insensitive), in layout order. */
internal fun scanForMatches(roots: List<SemanticsNode>, query: String): List<FindMatch> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val found = mutableListOf<FindMatch>()

    fun visit(node: SemanticsNode, out: MutableList<FindMatch>) {
        val config = node.config
        if (config.getOrNull(SemanticsProperties.TestTag) == FIND_IN_PAGE_TAG) return
        // Unplaced subtrees are screens kept composed behind the current one (NavDisplay).
        if (!node.layoutInfo.isPlaced) return

        val findable = config.getOrNull(FindableItemsKey)
        if (findable != null) {
            val composed = mutableListOf<FindMatch>()
            node.children.forEach { visit(it, composed) }
            out += mergeFindableList(node, findable, composed, needle)
            return
        }

        val text = node.searchableText()
        if (text != null && text.contains(needle, ignoreCase = true) && node.isPlacedUpToRoot()) {
            val visible = node.boundsInWindow
            if (!visible.isEmpty || node.hasVisibleScrollAncestor()) {
                out += FindMatch(key = node.id, node = node, visibleBounds = visible)
            }
        }
        node.children.forEach { visit(it, out) }
    }

    roots.forEach { visit(it, found) }
    return found
}

/**
 * Combines a findable lazy list's composed matches with matches in rows that are not composed,
 * in row order. Composed matches are tagged with their row so selection can land on them.
 */
private fun mergeFindableList(
    list: SemanticsNode,
    findable: FindableItems,
    composed: List<FindMatch>,
    needle: String,
): List<FindMatch> {
    val visibleRows = runCatching { findable.visibleItems() }.getOrDefault(emptyList())
    val listTop = list.positionInWindow.y
    val tagged = composed.map { match ->
        val centerY = match.node.positionInWindow.y + match.node.size.height / 2f
        val row = visibleRows.firstOrNull { row ->
            centerY >= listTop + row.offset && centerY < listTop + row.offset + row.size
        }
        FindMatch(
            key = match.key,
            node = match.node,
            visibleBounds = match.visibleBounds,
            listId = list.id,
            itemIndex = row?.index,
        )
    }

    // Unloaded rows count only while the list itself is on screen or reachable by scrolling.
    val listReachable = !list.boundsInWindow.isEmpty || list.hasVisibleScrollAncestor()
    val composedRows = visibleRows.mapTo(HashSet()) { it.index }
    val unloaded = if (!listReachable) {
        emptyList()
    } else {
        findable.itemTexts.indices
            .filter { index ->
                index !in composedRows && findable.itemTexts[index].contains(needle, ignoreCase = true)
            }
            .map { index ->
                FindMatch(
                    key = UnloadedRowKey(list.id, index),
                    node = list,
                    visibleBounds = Rect.Zero,
                    listId = list.id,
                    itemIndex = index,
                    scrollToRow = { findable.scrollToItem(index) },
                )
            }
    }

    // Stable sort: matches within one row keep their on-screen order.
    return (tagged + unloaded).sortedBy { it.itemIndex ?: -1 }
}

private fun SemanticsNode.searchableText(): String? {
    val config = config
    config.getOrNull(SemanticsProperties.EditableText)?.let { return it.text }
    config.getOrNull(SemanticsProperties.Text)
        ?.takeIf { it.isNotEmpty() }
        ?.let { parts -> return parts.joinToString(" ") { it.text } }
    // Images (posters whose title is only artwork) are findable by their description.
    return config.getOrNull(SemanticsProperties.ContentDescription)
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(" ")
}

/** A non-semantic layout between two semantic nodes can still be unplaced, so check them all. */
private fun SemanticsNode.isPlacedUpToRoot(): Boolean {
    var info = layoutInfo
    while (true) {
        if (!info.isAttached || !info.isPlaced) return false
        info = info.parentInfo ?: return true
    }
}

private fun SemanticsNode.hasVisibleScrollAncestor(): Boolean {
    var ancestor = parent
    while (ancestor != null) {
        if (SemanticsActions.ScrollBy in ancestor.config && !ancestor.boundsInWindow.isEmpty) return true
        ancestor = ancestor.parent
    }
    return false
}

private fun SemanticsNode.unclippedBoundsInWindow(): Rect =
    Rect(positionInWindow, size.toSize())

/**
 * Scrolls the nearest horizontal and vertical scroll containers so [node] is centred, the way a
 * browser brings the active match into view. Nothing moves when it is already fully visible.
 */
internal fun revealInScrollContainers(node: SemanticsNode) {
    val target = runCatching { node.unclippedBoundsInWindow() }.getOrNull() ?: return
    var horizontalDone = false
    var verticalDone = false
    var ancestor = node.parent
    while (ancestor != null && !(horizontalDone && verticalDone)) {
        val config = ancestor.config
        val scrollBy = config.getOrNull(SemanticsActions.ScrollBy)?.action
        val viewport = ancestor.boundsInWindow
        if (scrollBy != null && !viewport.isEmpty) {
            val horizontal = !horizontalDone && SemanticsProperties.HorizontalScrollAxisRange in config
            val vertical = !verticalDone && SemanticsProperties.VerticalScrollAxisRange in config
            val dx = if (horizontal && (target.left < viewport.left || target.right > viewport.right)) {
                target.center.x - viewport.center.x
            } else {
                0f
            }
            val dy = if (vertical && (target.top < viewport.top || target.bottom > viewport.bottom)) {
                target.center.y - viewport.center.y
            } else {
                0f
            }
            if (dx != 0f || dy != 0f) runCatching { scrollBy(dx, dy) }
            if (horizontal) horizontalDone = true
            if (vertical) verticalDone = true
        }
        ancestor = ancestor.parent
    }
}
