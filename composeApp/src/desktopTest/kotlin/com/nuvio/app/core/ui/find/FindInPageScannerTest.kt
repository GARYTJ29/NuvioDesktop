package com.nuvio.app.core.ui.find

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import com.nuvio.app.core.ui.findableItems
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs against a real window: the scanner depends on Compose Desktop's accessibility bridge,
 * which only exists for a shown window. Skipped on headless machines.
 */
class FindInPageScannerTest {

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun settle() = repeat(10) {
        onEdt { }
        Thread.sleep(50)
    }

    private fun withWindow(content: @Composable () -> Unit, block: (ComposeWindow) -> Unit) {
        if (GraphicsEnvironment.isHeadless()) return
        val window = onEdt {
            ComposeWindow().apply {
                setSize(400, 300)
                setContent { content() }
                isVisible = true
            }
        }
        try {
            settle()
            block(window)
        } finally {
            onEdt { window.dispose() }
        }
    }

    private fun scan(window: ComposeWindow, query: String) =
        onEdt { scanForMatches(findSemanticRoots(window), query) }

    @Test
    fun findsVisibleTextAndSkipsLazyItemsThatAreNotComposed() = withWindow(
        content = {
            LazyColumn(modifier = Modifier.height(200.dp)) {
                items(200) { index ->
                    BasicText(
                        text = if (index % 50 == 0) "Night of the Living Dead $index" else "Row $index",
                        modifier = Modifier.height(40.dp),
                    )
                }
            }
        },
    ) { window ->
        // Only item 0 is composed; items 50/100/150 are far below and do not exist yet.
        val visible = scan(window, "living dead")
        assertEquals(1, visible.size, "visible matches")
        assertTrue(!visible.single().visibleBounds.isEmpty, "match should be on screen")
        assertEquals(0, scan(window, "no such text").size)
    }

    @Test
    fun findsClippedTextInScrollColumnAndScrollsItIntoView() = withWindow(
        content = {
            Column(modifier = Modifier.height(200.dp).verticalScroll(rememberScrollState())) {
                repeat(40) { index ->
                    BasicText(
                        text = if (index == 30) "Public Domain Movies" else "Row $index",
                        modifier = Modifier.height(40.dp),
                    )
                }
            }
        },
    ) { window ->
        val hidden = scan(window, "public domain").single()
        assertTrue(hidden.visibleBounds.isEmpty, "row 30 starts scrolled out of view")

        onEdt { revealInScrollContainers(hidden.node) }
        settle()

        val revealed = scan(window, "public domain").single()
        assertEquals(hidden.key, revealed.key)
        assertTrue(!revealed.visibleBounds.isEmpty, "revealed match should be visible")
    }

    @Test
    fun findableListCountsUnloadedRowsAndSelectionLandsOnTheirText() = withWindow(
        content = {
            val state = rememberLazyListState()
            val texts = remember { List(200) { if (it == 150) "Public Domain Movies" else "Catalog $it" } }
            LazyColumn(
                state = state,
                modifier = Modifier.height(200.dp).findableItems(state, texts),
            ) {
                items(texts.size) { index -> BasicText(texts[index], modifier = Modifier.height(40.dp)) }
            }
        },
    ) { window ->
        // Row 150 is not composed, but the list's findable data still reports it.
        val unloaded = scan(window, "public domain").single()
        assertNotNull(unloaded.scrollToRow)
        assertEquals(150, unloaded.itemIndex)

        try {
            onEdt {
                FindInPage.updateQuery("public domain")
                FindInPage.publishMatches(scanForMatches(findSemanticRoots(window), "public domain")) // selects it and scrolls
            }
            settle()
            onEdt { FindInPage.publishMatches(scanForMatches(findSemanticRoots(window), "public domain")) }

            val current = onEdt { FindInPage.matches[FindInPage.currentIndex] }
            assertNull(current.scrollToRow, "selection should move to the row's real text")
            assertEquals(150, current.itemIndex)
            assertTrue(!current.visibleBounds.isEmpty, "selected row should be on screen")
        } finally {
            onEdt { FindInPage.close() }
        }
    }
}
