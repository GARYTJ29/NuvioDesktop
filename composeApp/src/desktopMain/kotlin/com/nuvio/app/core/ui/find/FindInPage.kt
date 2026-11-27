package com.nuvio.app.core.ui.find

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import com.nuvio.app.controller.ControllerManager
import com.nuvio.app.controller.ControllerScreenType
import java.awt.event.KeyEvent

/** Identifies a match in a findable list row that is not composed yet. */
internal data class UnloadedRowKey(val listId: Int, val itemIndex: Int)

/**
 * One match: a text element on screen, or a row of a findable lazy list that is not composed yet
 * (then [node] is the list and [scrollToRow] brings the row in so its real text can be selected).
 */
internal class FindMatch(
    /** Semantics node id for real text, [UnloadedRowKey] for an unloaded row. */
    val key: Any,
    val node: SemanticsNode,
    /** Bounds clipped to every ancestor; empty while scrolled out of view or not composed. */
    val visibleBounds: Rect,
    /** Findable lazy list containing this match and the row index in it, when known. */
    val listId: Int? = null,
    val itemIndex: Int? = null,
    val scrollToRow: (() -> Unit)? = null,
)

/**
 * Browser-style find in page (Ctrl+F / Cmd+F) for the desktop window.
 *
 * Text is read from the window's live Compose semantics tree, so every screen works without its
 * own wiring. Lazy lists and grids only compose items near the viewport, so items far off-screen
 * are found once they have been scrolled close enough to exist.
 */
internal object FindInPage {
    var isOpen by mutableStateOf(false)
        private set

    var query by mutableStateOf("")
        private set

    var matches by mutableStateOf<List<FindMatch>>(emptyList())
        private set

    var currentIndex by mutableIntStateOf(-1)
        private set

    /** Bumped on every Ctrl+F so the field refocuses and selects its text, like a browser. */
    var openRequests by mutableIntStateOf(0)
        private set

    /** Read by the AWT key dispatchers, so arrow keys move the caret instead of app focus. */
    @Volatile
    var isFieldFocused: Boolean = false

    private var currentMatchKey: Any? = null

    /** Row scrolled to for an unloaded match, whose real text gets selected once it is composed. */
    private var pendingRow: UnloadedRowKey? = null
    private var pendingRowScans = 0

    fun open() {
        isOpen = true
        openRequests++
    }

    fun close() {
        isOpen = false
        isFieldFocused = false
        matches = emptyList()
        currentIndex = -1
        currentMatchKey = null
        pendingRow = null
    }

    fun updateQuery(value: String) {
        if (value == query) return
        query = value
        // Restart from the first match for the new text, as a browser does while typing.
        currentMatchKey = null
        currentIndex = -1
        pendingRow = null
    }

    fun next() = step(1)

    fun previous() = step(-1)

    private fun step(delta: Int) {
        val current = matches
        if (current.isEmpty()) return
        val index = if (currentIndex < 0) {
            if (delta > 0) 0 else current.lastIndex
        } else {
            (currentIndex + delta).mod(current.size)
        }
        select(current, index)
    }

    /** Applies a fresh scan, keeping the current match when it is still on screen. */
    fun publishMatches(found: List<FindMatch>) {
        matches = found
        if (found.isEmpty()) {
            currentIndex = -1
            return
        }
        pendingRow?.let { row ->
            val landed = found.indexOfFirst { match ->
                match.scrollToRow == null && match.listId == row.listId && match.itemIndex == row.itemIndex
            }
            if (landed >= 0) {
                select(found, landed)
                return
            }
            // The row's text may not be rendered as written (or the list changed): stop waiting.
            if (++pendingRowScans > PENDING_ROW_MAX_SCANS) pendingRow = null
        }
        val keptIndex = currentMatchKey?.let { key -> found.indexOfFirst { it.key == key } } ?: -1
        when {
            keptIndex >= 0 -> currentIndex = keptIndex
            currentMatchKey == null -> select(found, 0)
            else -> currentIndex = currentIndex.coerceIn(0, found.lastIndex).also {
                currentMatchKey = found[it].key
            }
        }
    }

    private fun select(found: List<FindMatch>, index: Int) {
        val match = found[index]
        currentIndex = index
        currentMatchKey = match.key
        val scrollToRow = match.scrollToRow
        if (scrollToRow != null && match.listId != null && match.itemIndex != null) {
            pendingRow = UnloadedRowKey(match.listId, match.itemIndex)
            pendingRowScans = 0
            scrollToRow()
        } else {
            pendingRow = null
            revealInScrollContainers(match.node)
        }
    }

    private const val PENDING_ROW_MAX_SCANS = 10

    /**
     * Handles find shortcuts ahead of the app's own key handling. Returns true when consumed.
     * Runs on the AWT event thread, which is also the Compose UI thread on desktop.
     */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.id != KeyEvent.KEY_PRESSED) return false
        val ctrl = event.isControlDown
        val meta = event.isMetaDown
        // Exactly one of Ctrl/Cmd: Ctrl+Cmd+F is the macOS fullscreen shortcut.
        val shortcutModifier = (ctrl != meta) && !event.isAltDown

        if (event.keyCode == KeyEvent.VK_F && shortcutModifier && !event.isShiftDown) {
            if (isPlayerActive()) return false
            open()
            return true
        }
        if (!isOpen) return false

        return when {
            event.keyCode == KeyEvent.VK_ESCAPE -> {
                close()
                true
            }
            event.keyCode == KeyEvent.VK_F3 ||
                (event.keyCode == KeyEvent.VK_G && shortcutModifier) -> {
                if (event.isShiftDown) previous() else next()
                true
            }
            event.keyCode == KeyEvent.VK_ENTER && isFieldFocused -> {
                if (event.isShiftDown) previous() else next()
                true
            }
            else -> false
        }
    }

    fun isPlayerActive(): Boolean =
        ControllerManager.screenContext.value.screenType == ControllerScreenType.Player
}
