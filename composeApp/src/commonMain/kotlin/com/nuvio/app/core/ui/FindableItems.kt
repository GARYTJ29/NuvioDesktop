package com.nuvio.app.core.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch

/** A lazy-list row currently laid out: main-axis offset from the list's viewport start, in px. */
class FindableVisibleItem(val index: Int, val offset: Int, val size: Int)

/**
 * Lets desktop find-in-page (Ctrl+F) search a lazy list's rows that are not composed yet.
 * [itemTexts] holds each row's searchable text by index; [scrollToItem] brings a row into view.
 */
class FindableItems(
    val itemTexts: List<String>,
    val visibleItems: () -> List<FindableVisibleItem>,
    val scrollToItem: (Int) -> Unit,
)

val FindableItemsKey = SemanticsPropertyKey<FindableItems>("NuvioFindableItems")

/**
 * Marks a vertical lazy list as searchable by find in page, including off-screen rows.
 * Apply it to the LazyColumn's modifier; [itemTexts] must follow the list's item indices.
 */
@Composable
fun Modifier.findableItems(state: LazyListState, itemTexts: List<String>): Modifier {
    val scope = rememberCoroutineScope()
    val info = remember(state, itemTexts, scope) {
        FindableItems(
            itemTexts = itemTexts,
            visibleItems = {
                state.layoutInfo.visibleItemsInfo.map { FindableVisibleItem(it.index, it.offset, it.size) }
            },
            scrollToItem = { index -> scope.launch { state.scrollToItem(index) } },
        )
    }
    return semantics { this[FindableItemsKey] = info }
}
