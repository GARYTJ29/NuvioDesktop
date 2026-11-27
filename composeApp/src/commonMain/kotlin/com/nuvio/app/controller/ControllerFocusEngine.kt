/*
 * Ported from NuvioDeck (https://github.com/kllakm/NuvioDeck), GPLv3.
 * Spatial focus engine and controller hint UI for 10-foot navigation.
 */
package com.nuvio.app.controller

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import com.nuvio.app.controller.audio.ControllerSoundPlayer
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ControllerFocusZone {
    Navigation,
    Content,
    Overlay,
}

data class ControllerFocusNodeSnapshot(
    val id: String,
    val zone: ControllerFocusZone,
    val groupKey: String?,
    val selectLabel: String,
    val secondaryActionLabel: String?,
    val tertiaryActionLabel: String?,
    val hasSecondaryAction: Boolean,
    val hasTertiaryAction: Boolean,
    val isTextInput: Boolean = false,
)

internal class ControllerFocusNode(
    val id: String,
    var zone: ControllerFocusZone,
    var groupKey: String?,
    var boundsInRoot: Rect = Rect.Zero,
    var unclippedBoundsInRoot: Rect = Rect.Zero,
    var enabled: Boolean = true,
    var isDefaultFocus: Boolean = false,
    var isTextInput: Boolean = false,
    var selectLabel: String = "Select",
    var secondaryActionLabel: String? = null,
    var tertiaryActionLabel: String? = null,
    var onSelect: () -> Unit = {},
    var onSecondaryAction: (() -> Unit)? = null,
    var onTertiaryAction: (() -> Unit)? = null,
    var onFocused: (() -> Unit)? = null,
    var onFocusedWithOption: ((allowVerticalScroll: Boolean) -> Unit)? = null,
    var onMoveUp: (() -> Boolean)? = null,
    var onMoveDown: (() -> Boolean)? = null,
    var onMoveLeft: (() -> Boolean)? = null,
    var onMoveRight: (() -> Boolean)? = null,
    /** Puts Compose focus on this node's element; true when it took it. */
    var requestComposeFocus: (() -> Boolean)? = null,
    var rowLazyListState: LazyListState? = null,
    var rowItemIndex: Int = -1,
    var rowTotalCount: Int = 0,
    var coroutineScope: CoroutineScope? = null,
) {
    val isVisibleAndValid: Boolean
        get() = enabled && (
            (boundsInRoot.height > 2f && (
                boundsInRoot.width > 2f ||
                (rowLazyListState != null && unclippedBoundsInRoot.width > 2f && boundsInRoot.width >= 0f)
            )) ||
            (zone == ControllerFocusZone.Navigation && unclippedBoundsInRoot.height > 2f && unclippedBoundsInRoot.width > 2f)
        )

    fun toSnapshot(): ControllerFocusNodeSnapshot = ControllerFocusNodeSnapshot(
        id = id,
        zone = zone,
        groupKey = groupKey,
        selectLabel = selectLabel,
        secondaryActionLabel = secondaryActionLabel,
        tertiaryActionLabel = tertiaryActionLabel,
        hasSecondaryAction = onSecondaryAction != null,
        hasTertiaryAction = onTertiaryAction != null,
        isTextInput = isTextInput,
    )
}

data class ControllerGlobalHandlers(
    val onBack: (() -> Boolean)? = null,
    val onBumperLeft: (() -> Boolean)? = null,
    val onBumperRight: (() -> Boolean)? = null,
    val onTriggerLeft: (() -> Boolean)? = null,
    val onTriggerRight: (() -> Boolean)? = null,
    val onActionX: (() -> Boolean)? = null,
    val onActionY: (() -> Boolean)? = null,
    val onViewButton: (() -> Boolean)? = null,
    val onMenuButton: (() -> Boolean)? = null,
    val onEnterNavigationZone: (() -> Unit)? = null,
    val onExitNavigationZone: (() -> Unit)? = null,
)

val LocalControllerVerticalListState = compositionLocalOf<LazyListState?> { null }
val LocalControllerVerticalScrollState = compositionLocalOf<ScrollState?> { null }
val LocalControllerRowLazyListState = compositionLocalOf<LazyListState?> { null }
val LocalControllerGroupKey = compositionLocalOf<String?> { null }
val LocalControllerFocusZone = compositionLocalOf { ControllerFocusZone.Content }

@Composable
fun ProvideControllerRowLazyListState(
    state: LazyListState,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalControllerRowLazyListState provides state,
        content = content,
    )
}

object ControllerFocusEngine {
    private val nodes = LinkedHashMap<String, ControllerFocusNode>()

    private val _focusedNodeId = MutableStateFlow<String?>(null)
    val focusedNodeId: StateFlow<String?> = _focusedNodeId.asStateFlow()

    private val _focusedNodeSnapshot = MutableStateFlow<ControllerFocusNodeSnapshot?>(null)
    val focusedNodeSnapshot: StateFlow<ControllerFocusNodeSnapshot?> = _focusedNodeSnapshot.asStateFlow()

    private val _isNavigationZoneExpanded = MutableStateFlow(false)
    val isNavigationZoneExpanded: StateFlow<Boolean> = _isNavigationZoneExpanded.asStateFlow()

    private val _isTopBarVisibleByScroll = MutableStateFlow(true)
    val isTopBarVisibleByScroll: StateFlow<Boolean> = _isTopBarVisibleByScroll.asStateFlow()

    var isHomeTabActive: Boolean = true

    /**
     * True on routes that are not instrumented with [controllerFocusable] (details, streams, player,
     * settings leaves). Keyboard and D-pad navigation is then handled by the Compose focus bridge in
     * PlatformKeyboardNavigationHost instead of the spatial engine, which would otherwise move its
     * ring across off-screen poster nodes.
     */
    var deferNavigationToCompose: Boolean = false

    /**
     * True after the engine ran out of nodes in a direction and handed focus to Compose, so the
     * controls it does not know about (hero buttons, the search field, Discover and filter chips,
     * continue-watching cards) are reachable. Directions and A then go to the Compose focus bridge
     * until Compose focus lands on an engine node again, which takes it back.
     */
    var composeOwnsFocus: Boolean = false
        private set
    private var yieldedFromId: String? = null
    private var yieldedAt: TimeMark? = null
    private var awaitingFirstComposeMove = false

    private fun resetComposeHandoff() {
        composeOwnsFocus = false
        yieldedFromId = null
        yieldedAt = null
        awaitingFirstComposeMove = false
    }

    /**
     * Hands focus to Compose at [current], so the bridge's directional search continues from there.
     * Returns false (and changes nothing) when the node cannot take Compose focus.
     */
    private fun yieldToCompose(current: ControllerFocusNode): Boolean {
        val request = current.requestComposeFocus ?: return false
        // Controller events arrive on the polling thread; Compose focus must change on the UI thread.
        if (!runCatching { runOnUiThreadBlocking { request() } }.getOrDefault(false)) return false
        composeOwnsFocus = true
        yieldedFromId = current.id
        yieldedAt = TimeSource.Monotonic.markNow()
        awaitingFirstComposeMove = true
        _focusedNodeId.value = null
        _focusedNodeSnapshot.value = null
        return true
    }

    /** Engine node whose element currently holds Compose focus, if any. */
    private var composeFocusedNodeId: String? = null

    /**
     * Set by the platform focus bridge: clears Compose focus (FocusManager.clearFocus). The bridge
     * must post it to the UI thread itself, since the engine may call it from the controller thread.
     */
    var clearComposeFocus: (() -> Unit)? = null

    /**
     * Set by the platform: runs a block on the UI thread and returns its result, waiting when called
     * from another thread (controller events are handled on the controller polling thread). Unset,
     * the block runs on the calling thread.
     */
    var uiThreadRunner: ((() -> Boolean) -> Boolean)? = null

    private fun runOnUiThreadBlocking(block: () -> Boolean): Boolean =
        uiThreadRunner?.invoke(block) ?: block()

    /**
     * Drops Compose focus once the engine drives focus again. Left in place, it kept the sidebar
     * expanded (it opens while Compose focus is inside it) and, after a route change, made the
     * Compose bridge move focus around the hidden previous screen instead of the new one.
     */
    private fun dropComposeFocus() {
        composeFocusedNodeId = null
        runCatching { clearComposeFocus?.invoke() }
    }

    internal fun hasActiveOverlay(): Boolean =
        modalOverlayOpen || nodes.values.any { it.zone == ControllerFocusZone.Overlay && it.isVisibleAndValid }

    /**
     * True while a modal overlay (the poster/episode options menu) is open, whether or not its rows
     * are laid out yet. Its rows only count as engine overlay stops once they have bounds, which
     * they lack while the menu animates in; until then directions reached the screen behind.
     */
    private var modalOverlayOpen = false
    private var composeFocusBeforeModal: String? = null

    /** Called by the overlay's coordinator when a modal overlay opens or closes (UI thread). */
    fun onModalOverlayVisibilityChanged(open: Boolean) {
        if (modalOverlayOpen == open) return
        modalOverlayOpen = open
        if (!deferNavigationToCompose) {
            composeFocusBeforeModal = null
            return
        }
        if (open) {
            // On Compose-driven routes the background keeps Compose focus (the episode card the
            // menu was opened from); take it away so nothing behind the menu can be moved or pressed.
            composeFocusBeforeModal = composeFocusedNodeId
            val current = _focusedNodeId.value?.let { nodes[it] }
            if (current == null || current.zone != ControllerFocusZone.Overlay) {
                _focusedNodeId.value = null
                _focusedNodeSnapshot.value = null
            }
            dropComposeFocus()
        } else {
            // Put Compose focus back on the element the menu was opened from.
            val restoreId = composeFocusBeforeModal
            composeFocusBeforeModal = null
            val request = restoreId?.let { nodes[it] }?.takeIf { it.isVisibleAndValid }?.requestComposeFocus
            if (request != null) runCatching { runOnUiThreadBlocking { request() } }
        }
    }

    /**
     * On routes Compose drives (details, streams, settings) Compose focus is the selection. The
     * engine only follows it, so the ring, the focus scale and X (Options) apply to the element the
     * d-pad actually reached. Before, the engine focused whichever node composed (episode cards as
     * the row scrolled), and its ring, drawn in preference to Compose's, stayed stuck on that card.
     */
    private fun mirrorComposeFocus(node: ControllerFocusNode, focused: Boolean) {
        if (hasActiveOverlay()) return
        if (focused) {
            if (!node.isVisibleAndValid || _focusedNodeId.value == node.id) return
            _focusedNodeId.value = node.id
            _focusedNodeSnapshot.value = node.toSnapshot()
            lastFocusedByZone[node.zone] = node.id
        } else if (_focusedNodeId.value == node.id) {
            _focusedNodeId.value = null
            _focusedNodeSnapshot.value = null
        }
    }

    /** Compose focus moved to or from an engine node's element. */
    internal fun onComposeFocusChanged(node: ControllerFocusNode, focused: Boolean) {
        if (focused) {
            composeFocusedNodeId = node.id
        } else if (composeFocusedNodeId == node.id) {
            composeFocusedNodeId = null
        }
        if (deferNavigationToCompose) {
            mirrorComposeFocus(node, focused)
            return
        }
        if (!focused || !composeOwnsFocus || !node.isVisibleAndValid) return
        // The hand-off itself focuses the node it left from; that is not a return to it.
        val sinceYield = yieldedAt?.elapsedNow()
        if (node.id == yieldedFromId && sinceYield != null && sinceYield < 150.milliseconds) return
        hasUserNavigatedOnCurrentRoute = true
        focusNodeInternal(node, triggerScroll = false)
    }

    /**
     * Reported by the Compose focus bridge after it handled a direction while Compose owns focus.
     * When the very first move after a hand-off finds nothing either, the engine takes focus back
     * and does what it did before hand-offs existed (Up opens the sidebar).
     */
    fun onComposeNavigationResult(direction: ControllerDirection, moved: Boolean) {
        if (!composeOwnsFocus) return
        if (moved) {
            awaitingFirstComposeMove = false
            return
        }
        if (!awaitingFirstComposeMove) return
        val origin = yieldedFromId?.let { nodes[it] }?.takeIf { it.isVisibleAndValid } ?: return
        focusNodeInternal(origin, triggerScroll = false)
        if (direction == ControllerDirection.Up) focusNavigationZoneFrom(origin)
    }

    private val _routeGeneration = MutableStateFlow(0)
    /** Increments on every route/tab change; used to re-seed Compose focus for the new screen. */
    val routeGeneration: StateFlow<Int> = _routeGeneration.asStateFlow()

    fun setTopBarVisibleByScroll(visible: Boolean) {
        _isTopBarVisibleByScroll.value = visible
    }

    private val _hasInteractiveContent = MutableStateFlow(false)
    val hasInteractiveContent: StateFlow<Boolean> = _hasInteractiveContent.asStateFlow()

    private fun updateInteractiveContentStatus() {
        _hasInteractiveContent.value = nodes.values.any { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
    }

    private var lastFocusedByZone = mutableMapOf<ControllerFocusZone, String>()
    private var lastFocusedByGroup = mutableMapOf<String, String>()

    data class ControllerScrollableEntry(
        val lazyListState: LazyListState? = null,
        val scrollState: ScrollState? = null,
        val lazyGridState: LazyGridState? = null,
        val scope: CoroutineScope,
        /** Viewport bounds in root coordinates, tracked by [RegisterControllerVerticalScroll]. */
        var viewportTopInRoot: Float = Float.NaN,
        var viewportHeight: Float = 0f,
        var viewportLeftInRoot: Float = Float.NaN,
        var viewportWidth: Float = 0f,
    )

    private val registeredScrollables = mutableListOf<ControllerScrollableEntry>()
    private var activeCoroutineScope: CoroutineScope? = null
    private var activeVerticalScrollJob: Job? = null
    private var activeHorizontalScrollJob: Job? = null
    private val SnappyEaseOut = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f)
    private var manualSidebarOpen = false
    private var lastNavigationDirection: ControllerDirection? = null

    private data class RouteFocusMemory(
        val focusedNodeId: String?,
        val lastFocusedByZone: Map<ControllerFocusZone, String>,
        val lastFocusedByGroup: Map<String, String>,
    )

    private const val PENDING_RESTORE_POLL_MS = 40L
    private const val PENDING_RESTORE_TIMEOUT_MS = 1500L

    private val routeFocusHistory = mutableMapOf<String, RouteFocusMemory>()
    private var pendingRestoredNodeId: String? = null
    private var pendingRestoredAt = 0L
    private var pendingRestoreFallbackJob: Job? = null
    private var isInternalCleanupFocus: Boolean = false

    private val handlerStack = mutableListOf<Pair<String, ControllerGlobalHandlers>>()
    private var customPlayerEventHandler: ((ControllerEvent) -> Boolean)? = null

    fun setPlayerEventHandler(handler: ((ControllerEvent) -> Boolean)?) {
        customPlayerEventHandler = handler
    }

    fun registerGlobalHandlers(key: String, handlers: ControllerGlobalHandlers) {
        handlerStack.removeAll { it.first == key }
        handlerStack.add(key to handlers)
    }

    fun unregisterGlobalHandlers(key: String) {
        handlerStack.removeAll { it.first == key }
    }

    fun registerVerticalScrollable(
        lazyListState: LazyListState? = null,
        scrollState: ScrollState? = null,
        lazyGridState: LazyGridState? = null,
        scope: CoroutineScope? = null,
    ) {
        if (scope != null) activeCoroutineScope = scope
        if (scope == null) return
        registeredScrollables.removeAll {
            (lazyListState != null && it.lazyListState === lazyListState) ||
            (scrollState != null && it.scrollState === scrollState) ||
            (lazyGridState != null && it.lazyGridState === lazyGridState)
        }
        registeredScrollables.add(ControllerScrollableEntry(lazyListState, scrollState, lazyGridState, scope))
    }

    fun unregisterVerticalScrollable(
        lazyListState: LazyListState? = null,
        scrollState: ScrollState? = null,
        lazyGridState: LazyGridState? = null,
    ) {
        registeredScrollables.removeAll {
            (lazyListState != null && it.lazyListState === lazyListState) ||
            (scrollState != null && it.scrollState === scrollState) ||
            (lazyGridState != null && it.lazyGridState === lazyGridState)
        }
    }

    /** Records the on-screen bounds of a registered scroll container. */
    internal fun updateScrollableViewport(
        lazyListState: LazyListState? = null,
        scrollState: ScrollState? = null,
        lazyGridState: LazyGridState? = null,
        topInRoot: Float,
        height: Float,
        leftInRoot: Float = Float.NaN,
        width: Float = 0f,
    ) {
        registeredScrollables.firstOrNull {
            (lazyListState != null && it.lazyListState === lazyListState) ||
            (scrollState != null && it.scrollState === scrollState) ||
            (lazyGridState != null && it.lazyGridState === lazyGridState)
        }?.let { entry ->
            entry.viewportTopInRoot = topInRoot
            entry.viewportHeight = height
            entry.viewportLeftInRoot = leftInRoot
            entry.viewportWidth = width
        }
    }

    internal fun getActiveScrollable(): ControllerScrollableEntry? {
        val valid = registeredScrollables.filter { entry ->
            val l = entry.lazyListState
            val s = entry.scrollState
            val g = entry.lazyGridState
            (l != null && l.layoutInfo.visibleItemsInfo.isNotEmpty()) ||
            (g != null && g.layoutInfo.visibleItemsInfo.isNotEmpty()) ||
            (s != null && s.maxValue > 0) ||
            (l != null) || (g != null)
        }
        return valid.lastOrNull { entry ->
            entry.lazyListState?.layoutInfo?.visibleItemsInfo?.isNotEmpty() == true ||
            entry.lazyGridState?.layoutInfo?.visibleItemsInfo?.isNotEmpty() == true
        } ?: valid.lastOrNull() ?: registeredScrollables.lastOrNull()
    }

    /**
     * Keeps the spatial selection on screen after a programmatic or lazy-list scroll.
     *
     * Lazy containers can keep an item composed while it leaves the viewport, so the focused node
     * stays "valid" but is no longer visible and its ring disappears. When that happens, move the
     * selection to the nearest visible content item without scrolling again.
     */
    internal fun ensureFocusWithinActiveViewport() {
        val entry = getActiveScrollable() ?: return
        val viewportTop = entry.viewportTopInRoot.takeIf { it.isFinite() } ?: return
        val viewportHeight = entry.viewportHeight
        if (viewportHeight <= 0f) return
        val viewportBottom = viewportTop + viewportHeight
        val viewportLeft = entry.viewportLeftInRoot.takeIf { it.isFinite() } ?: Float.NEGATIVE_INFINITY
        val viewportRight = if (viewportLeft.isFinite()) viewportLeft + entry.viewportWidth else Float.POSITIVE_INFINITY

        val current = _focusedNodeId.value?.let { nodes[it] }?.takeIf { it.isVisibleAndValid } ?: return
        if (current.zone != ControllerFocusZone.Content) return

        // Only act when the focused item is completely outside the viewport. A card that merely
        // peeks in at the bottom (the next row) must keep the focus, otherwise the ring jumps to
        // the row below while the user is still on the current one.
        val bounds = current.boundsInRoot
        val overlapsVertically = bounds.bottom > viewportTop + 2f && bounds.top < viewportBottom - 2f
        if (overlapsVertically) return

        val candidates = nodes.values.filter {
            it.zone == ControllerFocusZone.Content &&
                it.id != current.id &&
                it.isVisibleAndValid &&
                it.boundsInRoot.top >= viewportTop + 2f &&
                it.boundsInRoot.bottom <= viewportBottom - 2f &&
                it.boundsInRoot.center.x in viewportLeft..viewportRight
        }
        if (candidates.isEmpty()) return

        val sameGroup = candidates.filter { it.groupKey != null && it.groupKey == current.groupKey }
        val pool = sameGroup.ifEmpty { candidates }
        val target = pool.minByOrNull { abs(it.boundsInRoot.center.y - bounds.center.y) } ?: return
        focusNodeInternal(target, triggerScroll = false)
    }

    fun setNavigationZoneExpanded(expanded: Boolean) {
        _isNavigationZoneExpanded.value = expanded
    }

    private var activeRouteKey: String? = null
    private var hasUserNavigatedOnCurrentRoute: Boolean = false

    private fun saveCurrentRouteFocusMemory(overrideNodeId: String? = null) {
        val routeKey = activeRouteKey ?: return
        val targetId = overrideNodeId ?: _focusedNodeId.value ?: lastFocusedByZone[ControllerFocusZone.Content]
        if (targetId != null) {
            routeFocusHistory[routeKey] = RouteFocusMemory(
                focusedNodeId = targetId,
                lastFocusedByZone = HashMap(lastFocusedByZone),
                lastFocusedByGroup = HashMap(lastFocusedByGroup),
            )
        }
    }

    fun onRouteOrTabChanged(routeKey: Any? = null) {
        val newRouteStr = routeKey?.toString()
        val routeChanged = activeRouteKey != null && activeRouteKey != newRouteStr
        if (routeChanged) {
            // Compose focus left on the previous screen (still composed under the new one) would
            // catch the Compose bridge's moves there instead of the new screen being seeded.
            if (composeOwnsFocus || composeFocusedNodeId != null) dropComposeFocus()
            resetComposeHandoff()
            saveCurrentRouteFocusMemory()
            _routeGeneration.value = _routeGeneration.value + 1
        }
        activeRouteKey = newRouteStr

        // Purge nodes whose enabled is false or whose bounds are empty
        val invalidIds = nodes.entries.filter { !it.value.isVisibleAndValid }.map { it.key }
        invalidIds.forEach { nodes.remove(it) }

        if (routeChanged) {
            pendingRestoreFallbackJob?.cancel()
            val savedMemory = newRouteStr?.let { routeFocusHistory[it] }
            if (savedMemory != null && savedMemory.focusedNodeId != null) {
                lastFocusedByZone.clear()
                lastFocusedByZone.putAll(savedMemory.lastFocusedByZone)
                lastFocusedByGroup.clear()
                lastFocusedByGroup.putAll(savedMemory.lastFocusedByGroup)
                manualSidebarOpen = false
                hasUserNavigatedOnCurrentRoute = true
                _isNavigationZoneExpanded.value = false

                val targetId = savedMemory.focusedNodeId
                val existingNode = nodes[targetId]?.takeIf { it.isVisibleAndValid }
                if (existingNode != null) {
                    pendingRestoredNodeId = null
                    focusNodeInternal(
                        existingNode,
                        triggerScroll = true,
                        suppressVerticalScroll = true,
                        suppressHorizontalScroll = false,
                    )
                    updateInteractiveContentStatus()
                    return
                } else {
                    _focusedNodeId.value = null
                    _focusedNodeSnapshot.value = null
                    pendingRestoredNodeId = targetId
                    pendingRestoredAt = System.currentTimeMillis()
                    val scope = activeCoroutineScope ?: nodes.values.firstNotNullOfOrNull { it.coroutineScope }
                    pendingRestoreFallbackJob = scope?.launch {
                        var waited = 0L
                        while (pendingRestoredNodeId == targetId && waited < PENDING_RESTORE_TIMEOUT_MS) {
                            delay(PENDING_RESTORE_POLL_MS)
                            waited += PENDING_RESTORE_POLL_MS
                            if (nodes[targetId]?.isVisibleAndValid == true) break
                        }
                        if (pendingRestoredNodeId == targetId) {
                            val lateNode = nodes[targetId]?.takeIf { it.isVisibleAndValid }
                            pendingRestoredNodeId = null
                            if (lateNode != null) {
                                focusNodeInternal(
                                    lateNode,
                                    triggerScroll = true,
                                    suppressVerticalScroll = true,
                                    suppressHorizontalScroll = false,
                                )
                            } else {
                                ensureValidFocus()
                            }
                        }
                    }
                    updateInteractiveContentStatus()
                    return
                }
            } else {
                pendingRestoredNodeId = null
                _focusedNodeId.value = null
                _focusedNodeSnapshot.value = null
                lastFocusedByZone.clear()
                lastFocusedByGroup.clear()
                manualSidebarOpen = false
                hasUserNavigatedOnCurrentRoute = false
                _isNavigationZoneExpanded.value = false
            }
        } else {
            val current = _focusedNodeId.value?.let { nodes[it] }
            if (current == null || !current.isVisibleAndValid) {
                _focusedNodeId.value = null
                _focusedNodeSnapshot.value = null
            }
        }
        updateInteractiveContentStatus()
        ensureValidFocus()
    }

    internal fun upsertNode(node: ControllerFocusNode) {
        nodes[node.id] = node
        updateInteractiveContentStatus()
        val currentId = _focusedNodeId.value
        val activeOverlayNodes = nodes.values.filter { it.zone == ControllerFocusZone.Overlay && it.isVisibleAndValid }
        if (activeOverlayNodes.isNotEmpty()) {
            val currentNode = currentId?.let { nodes[it] }
            if (currentNode == null || currentNode.zone != ControllerFocusZone.Overlay || !currentNode.isVisibleAndValid) {
                val target = activeOverlayNodes.firstOrNull { it.isDefaultFocus }
                    ?: activeOverlayNodes.minWithOrNull(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
                if (target != null) {
                    focusNodeInternal(target, triggerScroll = false)
                }
            } else if (currentNode.id == node.id) {
                _focusedNodeSnapshot.value = node.toSnapshot()
            }
            return
        }

        // Compose drives this route; the engine only mirrors Compose focus (mirrorComposeFocus).
        if (deferNavigationToCompose) {
            if (currentId == node.id) _focusedNodeSnapshot.value = node.toSnapshot()
            return
        }

        val pendingId = pendingRestoredNodeId
        if (pendingId != null) {
            if (node.id == pendingId && node.isVisibleAndValid) {
                pendingRestoreFallbackJob?.cancel()
                pendingRestoredNodeId = null
                _isNavigationZoneExpanded.value = false
                focusNodeInternal(
                    node,
                    triggerScroll = true,
                    suppressVerticalScroll = true,
                    suppressHorizontalScroll = false,
                )
                return
            }
            if (System.currentTimeMillis() - pendingRestoredAt < PENDING_RESTORE_TIMEOUT_MS) return
        }

        // Compose holds focus on purpose; nodes composing or moving must not pull it back.
        if (composeOwnsFocus) return

        if (restoreRebuiltFocus(node)) return

        if (currentId == node.id) {
            _focusedNodeSnapshot.value = node.toSnapshot()
            return
        }

        val currentNode = currentId?.let { nodes[it] }
        if (node.zone == ControllerFocusZone.Content && node.isVisibleAndValid) {
            val shouldPromoteDefaultNode = node.isDefaultFocus &&
                !hasUserNavigatedOnCurrentRoute &&
                currentNode?.isDefaultFocus != true
            if (shouldPromoteDefaultNode || currentNode == null || !currentNode.isVisibleAndValid || (currentNode.zone == ControllerFocusZone.Navigation && !manualSidebarOpen)) {
                if (node.isDefaultFocus || currentId == null || currentNode?.zone == ControllerFocusZone.Navigation) {
                    val contentNodes = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
                    val rememberedId = lastFocusedByZone[ControllerFocusZone.Content]
                    val rememberedNode = rememberedId?.let { id -> contentNodes.firstOrNull { it.id == id } }
                    val settingsSidebarTopNode = contentNodes
                        .filter { it.groupKey == "settings_sidebar" }
                        .minByOrNull { it.boundsInRoot.top }
                    val target = if (hasUserNavigatedOnCurrentRoute && rememberedNode != null) {
                        rememberedNode
                    } else {
                        // Several rows may mark their first card as the default entry point. Pick the
                        // topmost one (then leftmost) so the initial selection is the first card of
                        // the first shelf, not whichever row happened to compose first.
                        contentNodes.filter { it.isDefaultFocus }
                            .minWithOrNull(
                                compareBy<ControllerFocusNode> { (it.boundsInRoot.top / 32f).toInt() }
                                    .thenBy { it.boundsInRoot.left },
                            )
                            ?: rememberedNode
                            ?: settingsSidebarTopNode
                            ?: contentNodes.minWithOrNull(
                                compareBy<ControllerFocusNode> { (it.boundsInRoot.top / 32f).toInt() }
                                    .thenBy { it.boundsInRoot.left },
                            )
                    }
                    if (target != null) {
                        _isNavigationZoneExpanded.value = false
                        focusNodeInternal(target, triggerScroll = false)
                        return
                    }
                }
            }
        }
    }

    /**
     * A focused stop that left composition, kept for a moment in case it comes straight back: list
     * items that are rebuilt (their key changed, or the items above them came or went) dispose and
     * recreate their stops under the same id. Without this, focus fell to the nearest other stop
     * and stayed there. Also covers the stop an overlay (dropdown menu) should return to.
     */
    private var rebuildingFocusId: String? = null
    private var rebuildingSince: TimeMark? = null
    private var rebuildFallbackId: String? = null

    /** Puts focus back on a stop that was just rebuilt, while nothing else claimed focus since. */
    private fun restoreRebuiltFocus(node: ControllerFocusNode): Boolean {
        val wanted = rebuildingFocusId ?: return false
        val since = rebuildingSince
        if (since == null || since.elapsedNow() > 500.milliseconds) {
            rebuildingFocusId = null
            return false
        }
        if (node.id != wanted || !node.isVisibleAndValid || composeOwnsFocus) return false
        rebuildingFocusId = null
        if (_focusedNodeId.value != rebuildFallbackId) return false
        isInternalCleanupFocus = true
        try {
            focusNodeInternal(node, triggerScroll = false)
        } finally {
            isInternalCleanupFocus = false
        }
        return true
    }

    internal fun removeNode(id: String) {
        val removed = nodes.remove(id)
        if (_focusedNodeId.value == id) {
            _focusedNodeId.value = null
            _focusedNodeSnapshot.value = null
            val wantedBack = if (removed?.zone == ControllerFocusZone.Overlay) {
                lastFocusedByZone[ControllerFocusZone.Content]
            } else {
                id
            }
            isInternalCleanupFocus = true
            try {
                // If an overlay just closed, restore Content focus unless Navigation focus is actively selected
                if (removed?.zone == ControllerFocusZone.Overlay) {
                    val remainingOverlay = nodes.values.filter { it.zone == ControllerFocusZone.Overlay && it.isVisibleAndValid }
                    if (remainingOverlay.isNotEmpty()) {
                        focusNodeInternal(remainingOverlay.first(), triggerScroll = false)
                        return
                    }
                    val currentNav = _focusedNodeId.value?.let { nodes[it] }?.takeIf { it.isVisibleAndValid }
                    if (currentNav?.zone == ControllerFocusZone.Navigation) {
                        return
                    }
                    // Compose drives this route: go back to mirroring the Compose-focused element.
                    if (deferNavigationToCompose) {
                        composeFocusedNodeId?.let { nodes[it] }?.takeIf { it.isVisibleAndValid }?.let { held ->
                            _focusedNodeId.value = held.id
                            _focusedNodeSnapshot.value = held.toSnapshot()
                        }
                        return
                    }
                    val restoredContentId = lastFocusedByZone[ControllerFocusZone.Content]
                    val restoredContent = restoredContentId?.let { nodes[it] }?.takeIf { it.isVisibleAndValid }
                    if (restoredContent != null) {
                        focusNodeInternal(restoredContent, triggerScroll = false)
                        return
                    }
                }
                if (pendingRestoredNodeId == null) {
                    ensureValidFocus(preferBounds = removed?.boundsInRoot, preferZone = removed?.zone)
                }
            } finally {
                isInternalCleanupFocus = false
                if (wantedBack != null && _focusedNodeId.value != wantedBack) {
                    rebuildingFocusId = wantedBack
                    rebuildingSince = TimeSource.Monotonic.markNow()
                    rebuildFallbackId = _focusedNodeId.value
                }
            }
        }
        updateInteractiveContentStatus()
    }

    /**
     * Focuses the first visible text input (e.g. the search field) and activates it, as Y does.
     * Returns false when no text input is on screen.
     */
    fun focusVisibleTextInput(): Boolean {
        val textInputNode = nodes.values.firstOrNull { it.isTextInput && it.isVisibleAndValid } ?: return false
        ControllerSoundPlayer.playSelect()
        pendingRestoreFallbackJob?.cancel()
        pendingRestoredNodeId = null
        hasUserNavigatedOnCurrentRoute = true
        if (_focusedNodeId.value != textInputNode.id) {
            focusNodeInternal(textInputNode, triggerScroll = true)
        }
        textInputNode.onSelect()
        return true
    }

    /**
     * Scrolls the active container by one page. Used on Compose-driven screens where Compose focus
     * reaches the last focusable element but the page still has content below/above it.
     */
    fun scrollActivePage(down: Boolean): Boolean {
        val entry = getActiveScrollable() ?: return false
        val listState = entry.lazyListState
        val gridState = entry.lazyGridState
        val scrollState = entry.scrollState
        val viewport = listState?.layoutInfo?.viewportSize?.height?.toFloat()
            ?: gridState?.layoutInfo?.viewportSize?.height?.toFloat()
            ?: 720f
        val delta = if (down) viewport * 0.7f else -viewport * 0.7f
        entry.scope.launch {
            when {
                listState != null -> runCatching { listState.scrollBy(delta) }
                gridState != null -> runCatching { gridState.scrollBy(delta) }
                scrollState != null -> runCatching { scrollState.scrollBy(delta) }
            }
        }
        return true
    }

    /** Focuses the topmost node of a group (used by popups and sheets). */
    fun focusFirstInGroup(groupKey: String): Boolean {
        val node = nodes.values
            .filter { it.groupKey == groupKey && it.isVisibleAndValid }
            .minWithOrNull(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
            ?: return false
        focusNodeInternal(node, triggerScroll = false)
        return true
    }

    fun requestFocus(id: String, triggerScroll: Boolean = true): Boolean {
        val node = nodes[id]?.takeIf { it.isVisibleAndValid } ?: return false
        hasUserNavigatedOnCurrentRoute = true
        focusNodeInternal(node, triggerScroll = triggerScroll)
        return true
    }

    fun clearFocusInZone(zone: ControllerFocusZone) {
        val current = _focusedNodeId.value?.let { nodes[it] }
        if (current?.zone == zone) {
            _focusedNodeId.value = null
            _focusedNodeSnapshot.value = null
        }
    }

    /** Drops the spatial selection, e.g. when a Compose-driven gate (profile picker) takes over. */
    fun clearFocus() {
        _focusedNodeId.value = null
        _focusedNodeSnapshot.value = null
    }

    fun ensureValidFocus(
        preferBounds: Rect? = null,
        preferZone: ControllerFocusZone? = null,
    ): Boolean {
        val overlayNodes = nodes.values.filter { it.zone == ControllerFocusZone.Overlay && it.isVisibleAndValid }
        if (overlayNodes.isNotEmpty()) {
            val current = _focusedNodeId.value?.let { nodes[it] }
            if (current != null && current.zone == ControllerFocusZone.Overlay && current.isVisibleAndValid) {
                return true
            }
            val target = overlayNodes.firstOrNull { it.isDefaultFocus }
                ?: overlayNodes.minWithOrNull(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
            if (target != null) {
                focusNodeInternal(target, triggerScroll = false)
                return true
            }
        }

        if (composeOwnsFocus) return false

        // Compose drives this route: no engine focus of its own (it mirrors Compose focus instead).
        if (deferNavigationToCompose) {
            val current = _focusedNodeId.value?.let { nodes[it] }
            if (current == null || !current.isVisibleAndValid || current.id != composeFocusedNodeId) {
                _focusedNodeId.value = null
                _focusedNodeSnapshot.value = null
            }
            return false
        }

        if (pendingRestoredNodeId != null) {
            val pendingNode = nodes[pendingRestoredNodeId]?.takeIf { it.isVisibleAndValid }
            if (pendingNode != null) {
                pendingRestoreFallbackJob?.cancel()
                pendingRestoredNodeId = null
                focusNodeInternal(
                    pendingNode,
                    triggerScroll = true,
                    suppressVerticalScroll = true,
                    suppressHorizontalScroll = false,
                )
                return true
            }
            return false
        }

        val current = _focusedNodeId.value?.let { nodes[it] }
        if (current != null && current.isVisibleAndValid) {
            return true
        }

        val contentNodes = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
        if (contentNodes.isNotEmpty()) {
            // On Home prefer real shelf posters over header/search nodes when no memory is available,
            // so returning from a detail screen never parks focus in the search field.
            val candidates = if (isHomeTabActive) {
                contentNodes.filter { it.groupKey?.startsWith("shelf_") == true }.ifEmpty { contentNodes }
            } else {
                contentNodes
            }
            val routeSavedId = activeRouteKey?.let { routeFocusHistory[it]?.focusedNodeId }
            val rememberedId = routeSavedId ?: lastFocusedByZone[ControllerFocusZone.Content]
            val remembered = rememberedId?.let { id -> contentNodes.firstOrNull { it.id == id } }
            if (remembered != null) {
                focusNodeInternal(remembered, triggerScroll = false)
                return true
            }
            if (preferBounds != null && preferZone == ControllerFocusZone.Content) {
                val nearest = candidates.minByOrNull {
                    hypot(
                        (it.boundsInRoot.center.x - preferBounds.center.x).toDouble(),
                        (it.boundsInRoot.center.y - preferBounds.center.y).toDouble(),
                    )
                }
                if (nearest != null) {
                    focusNodeInternal(nearest, triggerScroll = false)
                    return true
                }
            }
            val defaultNode = candidates.firstOrNull { it.isDefaultFocus }
            val settingsSidebarTopNode = candidates
                .filter { it.groupKey == "settings_sidebar" }
                .minByOrNull { it.boundsInRoot.top }
            val topLeftNode = candidates.minWithOrNull(
                compareBy<ControllerFocusNode> { (it.boundsInRoot.top / 32f).toInt() }
                    .thenBy { it.boundsInRoot.left },
            )
            val chosen = defaultNode ?: remembered ?: settingsSidebarTopNode ?: topLeftNode
            if (chosen != null) {
                focusNodeInternal(chosen, triggerScroll = false)
                return true
            }
        }

        // Only fall back to the navigation rail when the user explicitly entered it. Screens that
        // are not instrumented with controllerFocusable must keep using Compose's directional focus
        // search instead of having their arrow keys silently consumed by the sidebar.
        if (manualSidebarOpen) {
            val navNodes = nodes.values.filter { it.zone == ControllerFocusZone.Navigation && it.isVisibleAndValid }
            if (navNodes.isNotEmpty()) {
                val chosen = navNodes.firstOrNull { it.isDefaultFocus } ?: navNodes.first()
                focusNodeInternal(chosen, triggerScroll = false)
                return true
            }
        }
        return false
    }

    fun focusNodeById(id: String, triggerScroll: Boolean = true): Boolean {
        hasUserNavigatedOnCurrentRoute = true
        val node = nodes[id]?.takeIf { it.isVisibleAndValid }
        if (node != null) {
            pendingRestoreFallbackJob?.cancel()
            pendingRestoredNodeId = null
            focusNodeInternal(node, triggerScroll = triggerScroll)
            return true
        }
        pendingRestoredNodeId = id
        return false
    }

    private fun focusNodeInternal(
        node: ControllerFocusNode,
        triggerScroll: Boolean,
        suppressVerticalScroll: Boolean = false,
        suppressHorizontalScroll: Boolean = false,
        direction: ControllerDirection? = null,
    ) {
        // On Compose-driven routes the engine must not draw its ring at all: Compose owns focus.
        // A stale ring here made the details rails look frozen while Compose moved its own focus.
        if (deferNavigationToCompose) {
            _focusedNodeId.value = null
            _focusedNodeSnapshot.value = null
            return
        }
        val previousId = _focusedNodeId.value
        analogScrolledSinceFocus = false
        // The engine takes over: Compose focus on any other element is stale now. Not on routes
        // Compose drives (details, streams, settings): there Compose focus is the real selection,
        // and the engine still tracks nodes (episode cards) as they compose while the row scrolls.
        val composeHeldId = composeFocusedNodeId
        if (!deferNavigationToCompose &&
            ((composeHeldId != null && composeHeldId != node.id) || (composeOwnsFocus && composeHeldId == null))
        ) {
            dropComposeFocus()
        }
        resetComposeHandoff()
        _focusedNodeId.value = node.id
        _focusedNodeSnapshot.value = node.toSnapshot()
        if (!isInternalCleanupFocus) {
            lastFocusedByZone[node.zone] = node.id
            node.groupKey?.let { group ->
                lastFocusedByGroup[group] = node.id
            }
            if (node.zone == ControllerFocusZone.Content) {
                saveCurrentRouteFocusMemory(node.id)
            }
        }
        if (node.zone == ControllerFocusZone.Navigation) {
            _isNavigationZoneExpanded.value = true
            _isTopBarVisibleByScroll.value = true
            invokeGlobalHandler { it.onEnterNavigationZone?.invoke(); false }
        } else if (node.zone == ControllerFocusZone.Content && _isNavigationZoneExpanded.value) {
            _isNavigationZoneExpanded.value = false
            manualSidebarOpen = false
            invokeGlobalHandler { it.onExitNavigationZone?.invoke(); false }
        }
        if (previousId != null && previousId != node.id && ControllerManager.isNavigationActive.value) {
            ControllerSoundPlayer.playNavigate()
        }
        if (triggerScroll) {
            node.onFocusedWithOption?.invoke(!suppressVerticalScroll) ?: node.onFocused?.invoke()
            if (!suppressVerticalScroll) {
                scrollVerticalContainerToNode(node, direction)
            }
            if (node.rowLazyListState != null && !suppressHorizontalScroll) {
                val effectiveDirection = direction ?: lastNavigationDirection
                scrollHorizontalContainerToNode(node, effectiveDirection)
            }
        }
    }

    private var isNavigatingLazyRow = false

    fun navigateLazyRowIndex(
        lazyListState: LazyListState,
        targetIndex: Int,
        totalItems: Int,
        isMovingRight: Boolean,
        coroutineScope: CoroutineScope,
        targetFocusId: String,
        edgeMarginPx: Float = 80f,
    ): Boolean {
        if (targetIndex < 0 || targetIndex >= totalItems) return false

        val layoutInfo = lazyListState.layoutInfo
        val viewportStart = layoutInfo.viewportStartOffset.toFloat()
        val viewportEnd = layoutInfo.viewportEndOffset.toFloat().takeIf { it > 100f } ?: 1920f
        val leftSafeZone = max(0f, viewportStart + edgeMarginPx)
        val rightSafeZone = (viewportEnd - max(layoutInfo.afterContentPadding.toFloat(), edgeMarginPx)).coerceAtLeast(leftSafeZone + 100f)

        val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }

        isNavigatingLazyRow = true
        activeHorizontalScrollJob?.cancel()

        if (targetItem != null) {
            val itemStart = targetItem.offset.toFloat()
            val itemEnd = (targetItem.offset + targetItem.size).toFloat()

            if (isMovingRight) {
                if (itemEnd <= rightSafeZone) {
                    requestFocus(targetFocusId, triggerScroll = false)
                    isNavigatingLazyRow = false
                    return true
                } else {
                    val delta = (itemEnd - rightSafeZone + 24f).coerceAtLeast(0f)
                    requestFocus(targetFocusId, triggerScroll = false)
                    activeHorizontalScrollJob = coroutineScope.launch {
                        try {
                            runCatching {
                                lazyListState.animateScrollBy(delta, tween(170, easing = SnappyEaseOut))
                            }
                            for (attempt in 0..5) {
                                if (requestFocus(targetFocusId, triggerScroll = false)) break
                                delay(30)
                            }
                        } finally {
                            isNavigatingLazyRow = false
                        }
                    }
                    return true
                }
            } else {
                // Moving Left
                if (targetIndex == 0) {
                    requestFocus(targetFocusId, triggerScroll = false)
                    if (lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0) {
                        isNavigatingLazyRow = false
                    } else {
                        activeHorizontalScrollJob = coroutineScope.launch {
                            try {
                                runCatching { lazyListState.animateScrollToItem(0, 0) }
                                for (attempt in 0..5) {
                                    if (requestFocus(targetFocusId, triggerScroll = false)) break
                                    delay(30)
                                }
                            } finally {
                                isNavigatingLazyRow = false
                            }
                        }
                    }
                    return true
                }

                if (itemStart >= leftSafeZone) {
                    requestFocus(targetFocusId, triggerScroll = false)
                    isNavigatingLazyRow = false
                    return true
                } else {
                    val delta = itemStart - leftSafeZone - 24f
                    requestFocus(targetFocusId, triggerScroll = false)
                    activeHorizontalScrollJob = coroutineScope.launch {
                        try {
                            runCatching {
                                lazyListState.animateScrollBy(delta, tween(170, easing = SnappyEaseOut))
                            }
                            for (attempt in 0..5) {
                                if (requestFocus(targetFocusId, triggerScroll = false)) break
                                delay(30)
                            }
                        } finally {
                            isNavigatingLazyRow = false
                        }
                    }
                    return true
                }
            }
        } else {
            // Target item is off-screen (not yet in visibleItemsInfo)
            if (isMovingRight) {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                val avgSize = layoutInfo.visibleItemsInfo.map { it.size }.average().toFloat().takeIf { it > 20f } ?: 220f
                val delta = if (lastVisible != null) {
                    ((lastVisible.offset + lastVisible.size) - rightSafeZone + avgSize + 32f).coerceAtLeast(avgSize)
                } else {
                    avgSize + edgeMarginPx
                }
                activeHorizontalScrollJob = coroutineScope.launch {
                    try {
                        runCatching {
                            lazyListState.animateScrollBy(delta, tween(180, easing = SnappyEaseOut))
                        }
                        for (attempt in 0..6) {
                            if (requestFocus(targetFocusId, triggerScroll = true)) break
                            delay(30)
                        }
                    } finally {
                        isNavigatingLazyRow = false
                    }
                }
                return true
            } else {
                // Moving Left off-screen
                if (targetIndex == 0) {
                    activeHorizontalScrollJob = coroutineScope.launch {
                        try {
                            runCatching { lazyListState.animateScrollToItem(0, 0) }
                            for (attempt in 0..6) {
                                if (requestFocus(targetFocusId, triggerScroll = false)) break
                                delay(30)
                            }
                        } finally {
                            isNavigatingLazyRow = false
                        }
                    }
                } else {
                    val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
                    val avgSize = layoutInfo.visibleItemsInfo.map { it.size }.average().toFloat().takeIf { it > 20f } ?: 220f
                    val delta = if (firstVisible != null) {
                        (firstVisible.offset - leftSafeZone - avgSize - 32f).coerceAtMost(-avgSize)
                    } else {
                        -(avgSize + edgeMarginPx)
                    }
                    activeHorizontalScrollJob = coroutineScope.launch {
                        try {
                            runCatching {
                                lazyListState.animateScrollBy(delta, tween(180, easing = SnappyEaseOut))
                            }
                            for (attempt in 0..6) {
                                if (requestFocus(targetFocusId, triggerScroll = true)) break
                                delay(30)
                            }
                        } finally {
                            isNavigatingLazyRow = false
                        }
                    }
                }
                return true
            }
        }
    }

    private fun scrollHorizontalContainerToNode(
        node: ControllerFocusNode,
        direction: ControllerDirection? = null,
    ) {
        if (isNavigatingLazyRow) return
        val rowState = node.rowLazyListState ?: return
        val scope = node.coroutineScope ?: activeCoroutineScope ?: return

        val layoutInfo = rowState.layoutInfo
        val viewportWidth = layoutInfo.viewportSize.width.toFloat().takeIf { it > 100f }
            ?: (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat().takeIf { it > 100f }
            ?: 1920f

        val unclipped = if (node.unclippedBoundsInRoot.width > 2f) node.unclippedBoundsInRoot else node.boundsInRoot
        val cardLeft = unclipped.left
        val cardRight = unclipped.right
        if (cardRight <= 0f && cardLeft <= 0f) return

        val safeLeft = max(layoutInfo.beforeContentPadding.toFloat(), 64f)
        val safeRight = (viewportWidth - max(layoutInfo.afterContentPadding.toFloat(), 64f)).coerceAtLeast(safeLeft + 120f)

        // 1. Vertical navigation (Up / Down) or focus restoration:
        // Keep horizontal offset stationary if card is already fully inside safe bounds; otherwise reveal it completely.
        if (direction == ControllerDirection.Up || direction == ControllerDirection.Down || direction == null) {
            if (cardLeft >= safeLeft && cardRight <= safeRight) {
                return
            }
            if (cardRight > safeRight && rowState.canScrollForward) {
                val delta = (cardRight - safeRight + 24f).coerceAtLeast(0f)
                if (delta > 2f) {
                    activeHorizontalScrollJob?.cancel()
                    activeHorizontalScrollJob = scope.launch {
                        runCatching { rowState.animateScrollBy(delta, tween(170, easing = SnappyEaseOut)) }
                    }
                }
            } else if (cardLeft < safeLeft && rowState.canScrollBackward) {
                if (node.rowItemIndex == 0) {
                    activeHorizontalScrollJob?.cancel()
                    activeHorizontalScrollJob = scope.launch {
                        runCatching { rowState.animateScrollToItem(0, 0) }
                    }
                } else {
                    val delta = (cardLeft - safeLeft - 24f).coerceAtMost(0f)
                    if (delta < -2f) {
                        activeHorizontalScrollJob?.cancel()
                        activeHorizontalScrollJob = scope.launch {
                            runCatching { rowState.animateScrollBy(delta, tween(170, easing = SnappyEaseOut)) }
                        }
                    }
                }
            }
            return
        }

        // 2. Horizontal Left navigation:
        // Bring the target card 100% into view on a single Left press.
        if (direction == ControllerDirection.Left) {
            val rowNodes = nodes.values.filter {
                it.rowLazyListState === rowState && it.isVisibleAndValid && !it.id.startsWith("view_all_pill_")
            }.sortedBy {
                val b = if (it.unclippedBoundsInRoot.width > 2f) it.unclippedBoundsInRoot else it.boundsInRoot
                b.left
            }
            val isLeftmostNode = rowNodes.firstOrNull()?.id == node.id
            val isFirstItemInRow = node.rowItemIndex == 0 ||
                (isLeftmostNode && rowState.firstVisibleItemIndex == 0)

            if (isFirstItemInRow) {
                if (rowState.canScrollBackward || rowState.firstVisibleItemScrollOffset > 0) {
                    activeHorizontalScrollJob?.cancel()
                    activeHorizontalScrollJob = scope.launch {
                        runCatching { rowState.animateScrollToItem(0, 0) }
                    }
                }
                return
            }

            if (cardLeft < safeLeft && rowState.canScrollBackward) {
                val delta = (cardLeft - safeLeft - 24f).coerceAtMost(0f)
                if (delta < -2f) {
                    activeHorizontalScrollJob?.cancel()
                    activeHorizontalScrollJob = scope.launch {
                        runCatching { rowState.animateScrollBy(delta, tween(170, easing = SnappyEaseOut)) }
                    }
                }
            }
            return
        }

        // 3. Horizontal Right navigation:
        // Calculate using full UNCLIPPED right edge so any partially off-screen poster snaps 100% into view on the first press.
        if (cardRight > safeRight && rowState.canScrollForward) {
            val delta = (cardRight - safeRight + 24f).coerceAtLeast(0f)
            if (delta > 2f) {
                activeHorizontalScrollJob?.cancel()
                activeHorizontalScrollJob = scope.launch {
                    runCatching { rowState.animateScrollBy(delta, tween(170, easing = SnappyEaseOut)) }
                }
            }
        }
    }

    private data class FocusContentRow(
        val top: Float,
        val bottom: Float,
        val centerY: Float,
        val nodes: List<ControllerFocusNode>,
        val groupKey: String?,
        val rowLazyListState: LazyListState?,
    )

    private fun getComposedContentRows(): List<FocusContentRow> {
        val contentNodes = nodes.values
            .filter {
                it.zone == ControllerFocusZone.Content &&
                    it.isVisibleAndValid &&
                    !it.id.startsWith("view_all_pill_") &&
                    it.groupKey != "settings_sidebar" &&
                    it.groupKey != "settings_content"
            }
            .sortedBy { it.boundsInRoot.center.y }
        if (contentNodes.isEmpty()) return emptyList()

        val rows = mutableListOf<MutableList<ControllerFocusNode>>()
        for (node in contentNodes) {
            val matchingRow = rows.firstOrNull { cluster ->
                val sample = cluster.first()
                if (node.rowLazyListState != null || sample.rowLazyListState != null) {
                    node.rowLazyListState != null && node.rowLazyListState === sample.rowLazyListState
                } else if (!node.groupKey.isNullOrEmpty() || !sample.groupKey.isNullOrEmpty()) {
                    node.groupKey == sample.groupKey
                } else {
                    val sampleCenterY = cluster.map { it.boundsInRoot.center.y }.average().toFloat()
                    val verticalOverlap = min(node.boundsInRoot.bottom, sample.boundsInRoot.bottom) -
                        max(node.boundsInRoot.top, sample.boundsInRoot.top)
                    val minHeight = min(node.boundsInRoot.height, sample.boundsInRoot.height).coerceAtLeast(1f)
                    val horizontalOverlap = cluster.maxOf { member ->
                        val hOverlap = min(node.boundsInRoot.right, member.boundsInRoot.right) -
                            max(node.boundsInRoot.left, member.boundsInRoot.left)
                        val minW = min(node.boundsInRoot.width, member.boundsInRoot.width).coerceAtLeast(1f)
                        hOverlap / minW
                    }
                    horizontalOverlap < 0.45f && ((verticalOverlap / minHeight) >= 0.35f || abs(node.boundsInRoot.center.y - sampleCenterY) <= 40f)
                }
            }
            if (matchingRow != null) {
                matchingRow.add(node)
            } else {
                rows.add(mutableListOf(node))
            }
        }

        return rows.map { list ->
            val sortedList = list.sortedBy { it.boundsInRoot.left }
            val minTop = sortedList.minOf { it.boundsInRoot.top }
            val maxBottom = sortedList.maxOf { it.boundsInRoot.bottom }
            val avgCenterY = sortedList.map { it.boundsInRoot.center.y }.average().toFloat()
            val sample = sortedList.first()
            FocusContentRow(
                top = minTop,
                bottom = maxBottom,
                centerY = avgCenterY,
                nodes = sortedList,
                groupKey = sample.groupKey,
                rowLazyListState = sample.rowLazyListState,
            )
        }.sortedBy { it.centerY }
    }

    private fun selectBestTargetNodeInRow(
        current: ControllerFocusNode,
        row: FocusContentRow,
    ): ControllerFocusNode? {
        val candidates = row.nodes.filter { !it.id.startsWith("view_all_pill_") }
        if (candidates.isEmpty()) return null

        // Move straight up/down: pick the card visually closest on the X axis, ignoring
        // which card was last focused in that row or its item index.
        val currentCenterX = current.boundsInRoot.center.x
        return candidates.minByOrNull { abs(it.boundsInRoot.center.x - currentCenterX) }
    }

    private fun scrollVerticalContainerToNode(
        node: ControllerFocusNode,
        direction: ControllerDirection? = null,
    ) {
        if (node.zone != ControllerFocusZone.Content) return
        val activeScroll = getActiveScrollable()
        val listState = activeScroll?.lazyListState
        val gridState = activeScroll?.lazyGridState
        val scrollState = activeScroll?.scrollState
        val scope = activeScroll?.scope ?: activeCoroutineScope ?: node.coroutineScope ?: return
        val bounds = node.boundsInRoot
        if (bounds.height <= 0f) return

        val isTopOfPageNode = node.groupKey == "home_hero" ||
            node.id.startsWith("home_hero_") ||
            node.id == "search_input_field" ||
            node.groupKey == "recent_searches" ||
            node.groupKey == "discover_filters"
        if (isTopOfPageNode) {
            _isTopBarVisibleByScroll.value = true
            activeVerticalScrollJob?.cancel()
            activeVerticalScrollJob = scope.launch {
                if (listState != null && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) {
                    runCatching { listState.animateScrollToItem(0, 0) }
                } else if (gridState != null && (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0)) {
                    runCatching { gridState.animateScrollToItem(0, 0) }
                } else if (scrollState != null && scrollState.value > 0) {
                    runCatching { scrollState.animateScrollTo(0) }
                }
            }
            return
        }

        if (direction == ControllerDirection.Up) {
            _isTopBarVisibleByScroll.value = true
        } else if (direction == ControllerDirection.Down && isHomeTabActive) {
            _isTopBarVisibleByScroll.value = false
        }

        activeVerticalScrollJob?.cancel()
        activeVerticalScrollJob = scope.launch {
            val vh = listState?.layoutInfo?.viewportSize?.height?.toFloat()
                ?: gridState?.layoutInfo?.viewportSize?.height?.toFloat()
                ?: 900f
            val scaleFactor = (vh / 900f).coerceIn(0.85f, 1.6f)

            val composedRows = getComposedContentRows()
            val targetRow = composedRows.firstOrNull { row -> row.nodes.any { it.id == node.id } }

            val rowCardTop: Float
            val rowCardBottom: Float
            if (targetRow != null) {
                rowCardTop = targetRow.top
                rowCardBottom = targetRow.bottom
            } else if (node.id.startsWith("view_all_pill_")) {
                val rowBelow = composedRows.firstOrNull {
                    it.top >= node.boundsInRoot.bottom - 40f && it.top <= node.boundsInRoot.bottom + 180f
                }
                rowCardTop = rowBelow?.top ?: (node.boundsInRoot.bottom + 15f)
                rowCardBottom = rowBelow?.bottom ?: (rowCardTop + 280f * scaleFactor)
            } else {
                rowCardTop = node.boundsInRoot.top
                rowCardBottom = node.boundsInRoot.bottom
            }

            val isShelfRow = node.rowLazyListState != null || targetRow?.rowLazyListState != null ||
                (!node.groupKey.isNullOrEmpty() && node.groupKey?.startsWith("shelf_") == true) ||
                node.id.startsWith("view_all_pill_")

            val headerClearance = if (isShelfRow) 52f * scaleFactor else 0f

            val rowPill = if (isShelfRow) {
                nodes.values.firstOrNull {
                    it.zone == ControllerFocusZone.Content &&
                        it.isVisibleAndValid &&
                        it.id.startsWith("view_all_pill_") &&
                        it.boundsInRoot.bottom <= rowCardTop + 40f &&
                        it.boundsInRoot.top >= rowCardTop - 200f
                }
            } else null

            val visualTop = if (isShelfRow) {
                minOf(
                    rowPill?.boundsInRoot?.top?.let { it - 12f * scaleFactor } ?: Float.MAX_VALUE,
                    rowCardTop - headerClearance
                )
            } else {
                rowCardTop
            }

            val visualBottom = if (isShelfRow) {
                rowCardBottom + (56f * scaleFactor)
            } else {
                rowCardBottom
            }

            // Visual vertical center of the active row including title header and poster card
            val visualRowCenterY = (visualTop + visualBottom) / 2f

            // Viewport vertical center in root coordinates. The scroll container may start below a
            // header/tab bar, so use its tracked bounds instead of assuming it starts at y = 0.
            val viewportTop = activeScroll?.viewportTopInRoot?.takeIf { it.isFinite() } ?: 0f
            val viewportHeight = activeScroll?.viewportHeight?.takeIf { it > 0f } ?: vh
            val viewportCenterY = viewportTop + viewportHeight * 0.52f

            var delta = visualRowCenterY - viewportCenterY

            // Directional scroll safety:
            // When navigating UP, we must NEVER scroll down (positive delta)
            if (direction == ControllerDirection.Up && delta > 0f) {
                delta = 0f
            }
            // When navigating DOWN, we must NEVER scroll up (negative delta)
            if (direction == ControllerDirection.Down && delta < 0f) {
                delta = 0f
            }

            // If non-shelf vertical content is already fully visible inside viewport safe margins, do not scroll
            val topChromeSafeMargin = viewportTop + 85f * scaleFactor
            val bottomSafeMargin = viewportTop + viewportHeight - (40f * scaleFactor)
            if (!isShelfRow && visualTop >= topChromeSafeMargin && visualBottom <= bottomSafeMargin) {
                delta = 0f
            }

            // When navigating horizontally within the same row, suppress vertical micro-jitter
            if (direction == ControllerDirection.Left || direction == ControllerDirection.Right) {
                if (abs(delta) < 32f) return@launch
            }

            // Boundary clamping at top of container: never overscroll past offset 0
            if (delta < 0f) {
                val maxScrollUp = if (listState?.firstVisibleItemIndex == 0) {
                    listState.firstVisibleItemScrollOffset.toFloat()
                } else if (gridState?.firstVisibleItemIndex == 0) {
                    gridState.firstVisibleItemScrollOffset.toFloat()
                } else if (scrollState != null) {
                    scrollState.value.toFloat()
                } else {
                    Float.MAX_VALUE
                }
                delta = delta.coerceAtLeast(-maxScrollUp)

                if (visualTop >= topChromeSafeMargin && (visualTop - delta) > (topChromeSafeMargin + 80f)) {
                    val clampedDelta = visualTop - topChromeSafeMargin
                    if (clampedDelta > delta) {
                        delta = clampedDelta
                    }
                }
            }

            if (abs(delta) > 4f) {
                // Center the newly focused row immediately. An animated scroll (300 ms) cannot
                // finish between key repeats, so rows used to stay pinned at the screen edge where
                // the ring was clipped and looked like it had disappeared.
                if (listState != null) {
                    runCatching { listState.scrollBy(delta) }
                } else if (gridState != null) {
                    runCatching { gridState.scrollBy(delta) }
                } else if (scrollState != null) {
                    runCatching { scrollState.scrollBy(delta) }
                }
            }
        }
    }
    fun handleEvent(event: ControllerEvent): Boolean {
        // If native player is active and handles the event directly, delegate first
        val overlayNodes = nodes.values.filter { it.zone == ControllerFocusZone.Overlay && it.isVisibleAndValid }
        if (overlayNodes.isEmpty()) {
            val playerHandler = customPlayerEventHandler
            if (playerHandler != null && playerHandler(event)) {
                return true
            }
        }

        // Routes without engine nodes belong to Compose: let the focus bridge move/activate the
        // Compose-focused element instead of dragging the spatial ring over hidden posters.
        // A modal overlay is open but its rows are not laid out yet (opening animation): swallow
        // movement and A, so they neither reach the screen behind nor act on its focused item.
        if (modalOverlayOpen && overlayNodes.isEmpty()) {
            when (event) {
                is ControllerEvent.Navigate -> return true
                is ControllerEvent.ButtonPressed -> when (event.button) {
                    XboxButton.A,
                    XboxButton.X,
                    XboxButton.DPadUp,
                    XboxButton.DPadDown,
                    XboxButton.DPadLeft,
                    XboxButton.DPadRight,
                    -> return true
                    else -> Unit
                }
                else -> Unit
            }
        }

        // An open overlay (episode options, poster actions) is an engine stop even on these routes.
        if (deferNavigationToCompose && !hasActiveOverlay()) {
            when (event) {
                is ControllerEvent.Navigate -> return false
                is ControllerEvent.ButtonPressed -> if (event.button == XboxButton.A) return false
                else -> Unit
            }
        }

        // Compose holds focus on a control the engine does not know: it moves and activates it.
        if (composeOwnsFocus && overlayNodes.isEmpty()) {
            when (event) {
                is ControllerEvent.Navigate -> return false
                is ControllerEvent.ButtonPressed -> when (event.button) {
                    XboxButton.A,
                    XboxButton.DPadUp,
                    XboxButton.DPadDown,
                    XboxButton.DPadLeft,
                    XboxButton.DPadRight,
                    -> return false
                    else -> Unit
                }
                else -> Unit
            }
        }

        return when (event) {
            is ControllerEvent.Navigate -> handleNavigate(event.direction)
            is ControllerEvent.ButtonPressed -> handleButtonPressed(event.button)
            is ControllerEvent.ButtonReleased -> false
            is ControllerEvent.AnalogScroll -> handleAnalogScroll(event.dx, event.dy).also { scrolled ->
                if (scrolled) analogScrolledSinceFocus = true
            }
        }
    }

    /**
     * Set when the right stick scrolls the page and cleared whenever focus moves. While set, the
     * focused item may have been scrolled out of view, and the next press should pick up from what
     * is on screen instead of scrolling back to it.
     */
    private var analogScrolledSinceFocus = false

    private fun ControllerFocusNode.isMostlyOnScreen(): Boolean {
        if (!isVisibleAndValid) return false
        val full = if (unclippedBoundsInRoot.height > 2f) unclippedBoundsInRoot else return true
        val heightShown = boundsInRoot.height >= full.height * 0.6f
        val widthShown = full.width <= 2f || boundsInRoot.width >= full.width * 0.6f
        return heightShown && widthShown
    }

    /**
     * After the right stick scrolled the focused item out of view, moves focus to the on-screen
     * item nearest to it and reports true; the press that triggered this only lands focus there.
     * Reports false when focus is still in view or there is nothing to land on.
     */
    private fun relandAfterAnalogScroll(): Boolean {
        if (!analogScrolledSinceFocus) return false
        analogScrolledSinceFocus = false
        if (nodes.values.any { it.zone == ControllerFocusZone.Overlay && it.isVisibleAndValid }) return false
        val current = _focusedNodeId.value?.let { nodes[it] }
        if (current != null && current.zone != ControllerFocusZone.Content) return false
        if (current != null && current.isMostlyOnScreen()) return false
        val candidates = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isMostlyOnScreen() }
        if (candidates.isEmpty()) return false
        val anchor = current?.let {
            if (it.unclippedBoundsInRoot.height > 2f) it.unclippedBoundsInRoot.center else it.boundsInRoot.center
        }
        val target = if (anchor != null) {
            candidates.minBy {
                hypot(
                    (it.boundsInRoot.center.x - anchor.x).toDouble(),
                    (it.boundsInRoot.center.y - anchor.y).toDouble(),
                )
            }
        } else {
            candidates.minWith(
                compareBy<ControllerFocusNode> { (it.boundsInRoot.top / 32f).toInt() }
                    .thenBy { it.boundsInRoot.left },
            )
        }
        focusNodeInternal(target, triggerScroll = false)
        return true
    }

    private fun handleAnalogScroll(dx: Float, dy: Float): Boolean {
        val activeScroll = getActiveScrollable()
        val listState = activeScroll?.lazyListState
        val gridState = activeScroll?.lazyGridState
        val scrollState = activeScroll?.scrollState
        val scope = activeScroll?.scope ?: activeCoroutineScope ?: nodes.values.firstNotNullOfOrNull { it.coroutineScope } ?: return false
        if (abs(dy) > 0.05f) {
            _isTopBarVisibleByScroll.value = dy < 0f
            val pixels = dy * 32f
            if (listState != null) {
                scope.launch { runCatching { listState.scrollBy(pixels) } }
                return true
            } else if (gridState != null) {
                scope.launch { runCatching { gridState.scrollBy(pixels) } }
                return true
            } else if (scrollState != null) {
                scope.launch { runCatching { scrollState.scrollBy(pixels) } }
                return true
            }
        }
        if (abs(dx) > 0.1f) {
            val current = _focusedNodeId.value?.let { nodes[it] }
            val rowState = current?.rowLazyListState
            if (rowState != null) {
                val pixels = dx * 24f
                scope.launch { runCatching { rowState.scrollBy(pixels) } }
                return true
            }
        }
        return false
    }

    private fun handleButtonPressed(button: XboxButton): Boolean {
        val current = _focusedNodeId.value?.let { nodes[it] }?.takeIf { it.isVisibleAndValid }
        return when (button) {
            XboxButton.DPadUp -> handleNavigate(ControllerDirection.Up)
            XboxButton.DPadDown -> handleNavigate(ControllerDirection.Down)
            XboxButton.DPadLeft -> handleNavigate(ControllerDirection.Left)
            XboxButton.DPadRight -> handleNavigate(ControllerDirection.Right)
            XboxButton.A -> {
                // Focus the stick scrolled out of view lands on screen first; A then selects it.
                if (relandAfterAnalogScroll()) return true
                ControllerSoundPlayer.playSelect()
                if (current == null) {
                    // Nothing spatially focused: seed the engine when possible, otherwise report the
                    // press as unhandled so the Compose focus bridge can activate the focused button.
                    ensureValidFocus()
                    false
                } else {
                    if (current.zone == ControllerFocusZone.Content) {
                        saveCurrentRouteFocusMemory(current.id)
                    }
                    if (current.isTextInput) {
                        PlatformVirtualKeyboard.show()
                    }
                    current.onSelect()
                    true
                }
            }
            XboxButton.B -> {
                ControllerSoundPlayer.playBack()
                if (PlatformVirtualKeyboard.isKeyboardActive) {
                    PlatformVirtualKeyboard.hide()
                    return true
                }
                PlatformVirtualKeyboard.hide()
                // If currently in Sidebar/Navigation zone, B returns focus to Content
                if (current?.zone == ControllerFocusZone.Navigation) {
                    manualSidebarOpen = false
                    val contentNodes = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
                    if (contentNodes.isNotEmpty()) {
                        val remembered = lastFocusedByZone[ControllerFocusZone.Content]?.let { id ->
                            contentNodes.firstOrNull { it.id == id }
                        } ?: contentNodes.first()
                        focusNodeInternal(remembered, triggerScroll = true)
                        return true
                    }
                }
                invokeGlobalHandler { it.onBack?.invoke() == true }
            }
            XboxButton.X -> {
                val secondary = current?.onSecondaryAction
                if (secondary != null) {
                    secondary.invoke()
                    true
                } else {
                    invokeGlobalHandler { it.onActionX?.invoke() == true }
                }
            }
            XboxButton.Y -> {
                // If a visible text input field exists on screen (e.g. search input field),
                // pressing Y immediately focuses it and invokes the virtual keyboard without requiring 'A' press confirmation
                if (focusVisibleTextInput()) {
                    true
                } else {
                    val tertiary = current?.onTertiaryAction
                    if (tertiary != null) {
                        tertiary.invoke()
                        true
                    } else {
                        invokeGlobalHandler { it.onActionY?.invoke() == true }
                    }
                }
            }
            XboxButton.LB -> invokeGlobalHandler { it.onBumperLeft?.invoke() == true }
            XboxButton.RB -> invokeGlobalHandler { it.onBumperRight?.invoke() == true }
            XboxButton.LT -> {
                if (invokeGlobalHandler { it.onTriggerLeft?.invoke() == true }) {
                    true
                } else {
                    scrollPage(up = true)
                }
            }
            XboxButton.RT -> {
                if (invokeGlobalHandler { it.onTriggerRight?.invoke() == true }) {
                    true
                } else {
                    scrollPage(up = false)
                }
            }
            XboxButton.View -> {
                if (invokeGlobalHandler { it.onViewButton?.invoke() == true }) {
                    true
                } else {
                    toggleNavigationZoneFocus()
                }
            }
            XboxButton.Menu -> {
                if (invokeGlobalHandler { it.onMenuButton?.invoke() == true }) {
                    true
                } else {
                    toggleNavigationZoneFocus()
                }
            }
            XboxButton.LS, XboxButton.RS -> false
        }
    }

    private fun toggleNavigationZoneFocus(): Boolean {
        val current = _focusedNodeId.value?.let { nodes[it] }
        if (current?.zone == ControllerFocusZone.Navigation) {
            manualSidebarOpen = false
            val contentNodes = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
            val target = lastFocusedByZone[ControllerFocusZone.Content]?.let { id ->
                contentNodes.firstOrNull { it.id == id }
            } ?: contentNodes.firstOrNull()
            if (target != null) {
                focusNodeInternal(target, triggerScroll = true)
                return true
            }
        } else {
            manualSidebarOpen = true
            val navNodes = nodes.values.filter { it.zone == ControllerFocusZone.Navigation && it.isVisibleAndValid }
            val target = lastFocusedByZone[ControllerFocusZone.Navigation]?.let { id ->
                navNodes.firstOrNull { it.id == id }
            } ?: navNodes.firstOrNull { it.isDefaultFocus } ?: navNodes.firstOrNull()
            if (target != null) {
                focusNodeInternal(target, triggerScroll = false)
                return true
            }
        }
        return false
    }

    private fun scrollPage(up: Boolean): Boolean {
        val activeScroll = getActiveScrollable()
        val listState = activeScroll?.lazyListState
        val gridState = activeScroll?.lazyGridState
        val scrollState = activeScroll?.scrollState
        val scope = activeScroll?.scope ?: activeCoroutineScope ?: nodes.values.firstNotNullOfOrNull { it.coroutineScope } ?: return false
        val delta = if (up) -520f else 520f
        if (listState != null) {
            scope.launch {
                runCatching { listState.animateScrollBy(delta, tween(220)) }
                delay(240)
                val visibleContent = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
                val target = if (up) {
                    visibleContent.minByOrNull { it.boundsInRoot.top }
                } else {
                    visibleContent.maxByOrNull { it.boundsInRoot.bottom }
                }
                if (target != null) {
                    focusNodeInternal(target, triggerScroll = false)
                }
            }
            return true
        } else if (gridState != null) {
            scope.launch {
                runCatching { gridState.animateScrollBy(delta, tween(220)) }
                delay(240)
                val visibleContent = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
                val target = if (up) {
                    visibleContent.minByOrNull { it.boundsInRoot.top }
                } else {
                    visibleContent.maxByOrNull { it.boundsInRoot.bottom }
                }
                if (target != null) {
                    focusNodeInternal(target, triggerScroll = false)
                }
            }
            return true
        } else if (scrollState != null) {
            scope.launch {
                runCatching { scrollState.animateScrollBy(delta, tween(220)) }
            }
            return true
        }
        return false
    }

    private fun invokeGlobalHandler(block: (ControllerGlobalHandlers) -> Boolean): Boolean {
        for (i in handlerStack.indices.reversed()) {
            if (block(handlerStack[i].second)) return true
        }
        return false
    }

    private fun handleContentVerticalNavigation(
        current: ControllerFocusNode,
        direction: ControllerDirection,
    ): Boolean {
        if (direction == ControllerDirection.Up) {
            _isTopBarVisibleByScroll.value = true
        } else if (direction == ControllerDirection.Down && isHomeTabActive) {
            _isTopBarVisibleByScroll.value = false
        }

        // Settings Column Isolation: Up and Down stay strictly within current column
        val isCurrentSettingsSidebar = current.groupKey == "settings_sidebar" || current.id.startsWith("settings_sidebar_")
        val isCurrentSettingsContent = current.groupKey == "settings_content" || (current.id.startsWith("settings_") && !isCurrentSettingsSidebar)

        if (isCurrentSettingsSidebar || isCurrentSettingsContent) {
            val columnNodes = nodes.values.filter {
                it.zone == ControllerFocusZone.Content &&
                    it.isVisibleAndValid &&
                    (if (isCurrentSettingsSidebar) (it.groupKey == "settings_sidebar" || it.id.startsWith("settings_sidebar_"))
                     else (it.groupKey == "settings_content" || (it.id.startsWith("settings_") && !it.id.startsWith("settings_sidebar_")))) &&
                    it.id != current.id
            }
            val target = if (direction == ControllerDirection.Down) {
                columnNodes.filter { it.boundsInRoot.top >= current.boundsInRoot.bottom - 10f || it.boundsInRoot.center.y > current.boundsInRoot.center.y + 10f }
                    .minByOrNull { it.boundsInRoot.top }
            } else {
                columnNodes.filter { it.boundsInRoot.bottom <= current.boundsInRoot.top + 10f || it.boundsInRoot.center.y < current.boundsInRoot.center.y - 10f }
                    .maxByOrNull { it.boundsInRoot.bottom }
            }
            if (target != null) {
                focusNodeInternal(target, triggerScroll = true, direction = direction)
                return true
            }
            return true
        }

        if (direction == ControllerDirection.Up && current.groupKey == "folder_tabs") {
            // Category tabs sit at the very top of a catalog; Up must not scroll the grid below.
            return true
        }

        val composedRows = getComposedContentRows()

        // 1. Strict handling if currently focused on a row's chevron ("See All" arrow):
        if (current.id.startsWith("view_all_pill_")) {
            if (direction == ControllerDirection.Down) {
                // Pressing Down MUST return focus back to the poster cards in that exact same row!
                val sameRowCards = nodes.values.filter {
                    it.zone == ControllerFocusZone.Content &&
                        it.isVisibleAndValid &&
                        !it.id.startsWith("view_all_pill_") &&
                        it.boundsInRoot.top >= current.boundsInRoot.bottom - 10f &&
                        it.boundsInRoot.top <= current.boundsInRoot.bottom + 220f
                }
                val target = sameRowCards.maxByOrNull { it.boundsInRoot.center.x }
                    ?: composedRows.firstOrNull { it.centerY > current.boundsInRoot.center.y }?.nodes?.lastOrNull()
                if (target != null) {
                    focusNodeInternal(target, triggerScroll = true, direction = direction)
                    return true
                }
            } else if (direction == ControllerDirection.Up) {
                // Pressing Up from the chevron moves to a poster card in the row above!
                val rowAbove = composedRows.lastOrNull { it.bottom < current.boundsInRoot.top + 30f }
                if (rowAbove != null) {
                    val target = selectBestTargetNodeInRow(current, rowAbove)
                    if (target != null) {
                        focusNodeInternal(target, triggerScroll = true, direction = direction)
                        return true
                    }
                }
            }
        }

        val rawRowIndex = composedRows.indexOfFirst { row -> row.nodes.any { it.id == current.id } }
        val effectiveRowIndex = if (rawRowIndex != -1) {
            rawRowIndex
        } else {
            val currentCenterY = current.boundsInRoot.center.y
            composedRows.indices.minByOrNull { abs(composedRows[it].centerY - currentCenterY) } ?: 0
        }
        val currentRow = composedRows[effectiveRowIndex]

        // 2. Strict Rule for Row Header Chevrons:
        // A row's chevron can be focused when:
        // (1) The user is currently focused on the very last (rightmost) poster card of that exact same row, AND
        // (2) The user presses Up.
        if (direction == ControllerDirection.Up && !current.id.startsWith("view_all_pill_")) {
            val isFarRightCard = (
                current.id == currentRow.nodes.lastOrNull()?.id ||
                (current.rowItemIndex >= 0 && current.rowTotalCount > 0 && current.rowItemIndex == current.rowTotalCount - 1) ||
                currentRow.nodes.none { it.boundsInRoot.center.x > current.boundsInRoot.center.x + 15f }
            )
            if (isFarRightCard) {
                val chevrons = nodes.values.filter {
                    it.zone == ControllerFocusZone.Content && it.isVisibleAndValid && it.id.startsWith("view_all_pill_")
                }
                val chevronAbove = chevrons.firstOrNull {
                    it.boundsInRoot.bottom <= current.boundsInRoot.top + 35f &&
                        it.boundsInRoot.top >= current.boundsInRoot.top - 180f
                }
                if (chevronAbove != null) {
                    focusNodeInternal(chevronAbove, triggerScroll = true, direction = direction)
                    return true
                }
            }
        }

        val activeScroll = getActiveScrollable()
        val listState = activeScroll?.lazyListState
        val gridState = activeScroll?.lazyGridState
        val scrollState = activeScroll?.scrollState
        val scope = activeScroll?.scope ?: activeCoroutineScope ?: current.coroutineScope

        if (direction == ControllerDirection.Down) {
            if (isHomeTabActive) {
                _isTopBarVisibleByScroll.value = false
            }
            if (effectiveRowIndex < composedRows.lastIndex) {
                val nextRow = composedRows[effectiveRowIndex + 1]
                val targetNode = selectBestTargetNodeInRow(current, nextRow)
                if (targetNode != null) {
                    focusNodeInternal(targetNode, triggerScroll = true, direction = direction)
                    return true
                }
            }

            val canScrollDown = (listState != null && listState.canScrollForward) ||
                (gridState != null && gridState.canScrollForward) ||
                (scrollState != null && scrollState.canScrollForward)

            if (canScrollDown && scope != null) {
                // A held direction must not restart an in-flight scroll: cancelling the animation
                // on every key repeat kept focus on a row that was about to be recycled, so the
                // selection vanished and navigation crawled.
                if (activeVerticalScrollJob?.isActive == true) return true
                val vh = listState?.layoutInfo?.viewportSize?.height?.toFloat()
                    ?: gridState?.layoutInfo?.viewportSize?.height?.toFloat()
                    ?: 900f
                val scaleFactor = (vh / 900f).coerceIn(0.85f, 1.6f)
                val scrollDistance = (currentRow.bottom - currentRow.top + 160f * scaleFactor).coerceIn(380f * scaleFactor, 540f * scaleFactor)

                activeVerticalScrollJob?.cancel()
                activeVerticalScrollJob = scope.launch {
                    val fallbackSpec = tween<Float>(200, easing = FastOutSlowInEasing)
                    if (listState != null) {
                        runCatching { listState.animateScrollBy(scrollDistance, fallbackSpec) }
                    } else if (gridState != null) {
                        runCatching { gridState.animateScrollBy(scrollDistance, fallbackSpec) }
                    } else if (scrollState != null) {
                        runCatching { scrollState.animateScrollBy(scrollDistance, fallbackSpec) }
                    }
                    delay(220)
                    val refreshedRows = getComposedContentRows()
                    val currentInRefreshed = refreshedRows.indexOfFirst { r ->
                        (currentRow.rowLazyListState != null && r.rowLazyListState === currentRow.rowLazyListState) ||
                        (!currentRow.groupKey.isNullOrEmpty() && r.groupKey == currentRow.groupKey) ||
                        r.nodes.any { n -> currentRow.nodes.any { it.id == n.id } }
                    }
                    val targetRow = if (currentInRefreshed != -1 && currentInRefreshed < refreshedRows.lastIndex) {
                        refreshedRows[currentInRefreshed + 1]
                    } else {
                        refreshedRows.lastOrNull()
                    }
                    val target = targetRow?.let { selectBestTargetNodeInRow(current, it) }
                    if (target != null) {
                        focusNodeInternal(target, triggerScroll = true, suppressVerticalScroll = false, direction = direction)
                    } else {
                        ensureValidFocus()
                    }
                }
                return true
            }
            // Nothing further down for the engine: let Compose reach controls below the last row.
            if (yieldToCompose(current)) return false
            return true
        } else if (direction == ControllerDirection.Up) {
            // The Home hero lives outside the shelf rows; focus it explicitly when the user moves
            // up from the first row so Up reaches "View Details" before the top bar.
            if (effectiveRowIndex <= 0) {
                val heroNode = nodes.values.firstOrNull {
                    it.groupKey == "home_hero" &&
                        it.isVisibleAndValid &&
                        it.boundsInRoot.center.y < current.boundsInRoot.center.y - 8f
                }
                if (heroNode != null) {
                    focusNodeInternal(heroNode, triggerScroll = true, direction = direction)
                    return true
                }
            }
            if (effectiveRowIndex > 0) {
                val prevRow = composedRows[effectiveRowIndex - 1]
                val targetNode = selectBestTargetNodeInRow(current, prevRow)
                if (targetNode != null) {
                    focusNodeInternal(targetNode, triggerScroll = true, direction = direction)
                    return true
                }
            }

            val canScrollUp = (listState != null && listState.canScrollBackward) ||
                (gridState != null && gridState.canScrollBackward) ||
                (scrollState != null && scrollState.canScrollBackward)

            if (canScrollUp && scope != null) {
                if (activeVerticalScrollJob?.isActive == true) return true
                val vh = listState?.layoutInfo?.viewportSize?.height?.toFloat()
                    ?: gridState?.layoutInfo?.viewportSize?.height?.toFloat()
                    ?: 900f
                val scaleFactor = (vh / 900f).coerceIn(0.85f, 1.6f)
                val scrollDistance = (currentRow.bottom - currentRow.top + 160f * scaleFactor).coerceIn(380f * scaleFactor, 540f * scaleFactor)

                val isAtTopBoundary = (listState != null && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= scrollDistance) ||
                    (gridState != null && gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset <= scrollDistance) ||
                    (scrollState != null && scrollState.value <= scrollDistance)

                activeVerticalScrollJob?.cancel()
                activeVerticalScrollJob = scope.launch {
                    if (isAtTopBoundary) {
                        if (listState != null) runCatching { listState.animateScrollToItem(0, 0) }
                        else if (gridState != null) runCatching { gridState.animateScrollToItem(0, 0) }
                        else if (scrollState != null) runCatching { scrollState.animateScrollTo(0) }
                    } else {
                        val fallbackSpec = tween<Float>(200, easing = FastOutSlowInEasing)
                        if (listState != null) runCatching { listState.animateScrollBy(-scrollDistance, fallbackSpec) }
                        else if (gridState != null) runCatching { gridState.animateScrollBy(-scrollDistance, fallbackSpec) }
                        else if (scrollState != null) runCatching { scrollState.animateScrollBy(-scrollDistance, fallbackSpec) }
                    }
                    delay(220)
                    val refreshedRows = getComposedContentRows()
                    val currentInRefreshed = refreshedRows.indexOfFirst { r ->
                        (currentRow.rowLazyListState != null && r.rowLazyListState === currentRow.rowLazyListState) ||
                        (!currentRow.groupKey.isNullOrEmpty() && r.groupKey == currentRow.groupKey) ||
                        r.nodes.any { n -> currentRow.nodes.any { it.id == n.id } }
                    }
                    val targetRow = if (currentInRefreshed > 0) {
                        refreshedRows[currentInRefreshed - 1]
                    } else {
                        refreshedRows.firstOrNull()
                    }
                    val target = targetRow?.let { selectBestTargetNodeInRow(current, it) }
                    if (target != null) {
                        focusNodeInternal(target, triggerScroll = true, suppressVerticalScroll = false, direction = direction)
                    } else {
                        ensureValidFocus()
                    }
                }
                return true
            }

            // Top of the engine's rows. Controls above them that are not engine nodes (hero
            // buttons, the search field, Discover type and filter chips) belong to Compose: hand
            // focus over. When Compose has nothing above either, it hands back and the sidebar opens.
            if (yieldToCompose(current)) return false
            focusNavigationZoneFrom(current)
            return true
        }

        return false
    }

    private fun focusNavigationZoneFrom(current: ControllerFocusNode): Boolean {
        val navNodes = nodes.values.filter { it.zone == ControllerFocusZone.Navigation && it.isVisibleAndValid }
        if (navNodes.isEmpty()) return false
        val target = navNodes.firstOrNull { it.isDefaultFocus }
            ?: lastFocusedByZone[ControllerFocusZone.Navigation]?.let { id -> navNodes.firstOrNull { it.id == id } }
            ?: navNodes.minByOrNull { abs(it.boundsInRoot.center.x - current.boundsInRoot.center.x) }
            ?: return false
        focusNodeInternal(target, triggerScroll = false, direction = ControllerDirection.Up)
        return true
    }

    private fun handleNavigate(direction: ControllerDirection): Boolean {
        hasUserNavigatedOnCurrentRoute = true
        lastNavigationDirection = direction
        if (PlatformVirtualKeyboard.isKeyboardActive) {
            PlatformVirtualKeyboard.hide()
        }
        if (relandAfterAnalogScroll()) return true
        val overlayNodes = nodes.values.filter { it.zone == ControllerFocusZone.Overlay && it.isVisibleAndValid }
        val activeZone = if (overlayNodes.isNotEmpty()) {
            ControllerFocusZone.Overlay
        } else {
            val current = _focusedNodeId.value?.let { nodes[it] }?.takeIf { it.isVisibleAndValid }
            if (current == null) {
                return ensureValidFocus()
            }
            current.zone
        }

        val current = _focusedNodeId.value?.let { nodes[it] }?.takeIf { it.isVisibleAndValid && it.zone == activeZone }
            ?: run {
                return ensureValidFocus()
            }

        // Check custom node directional overrides first
        val customHandled = when (direction) {
            ControllerDirection.Up -> current.onMoveUp?.invoke() == true
            ControllerDirection.Down -> current.onMoveDown?.invoke() == true
            ControllerDirection.Left -> current.onMoveLeft?.invoke() == true
            ControllerDirection.Right -> current.onMoveRight?.invoke() == true
        }
        if (customHandled) return true

        // Deterministic Netflix/Android TV row navigation for Content zone Up and Down
        if (activeZone == ControllerFocusZone.Content &&
            (direction == ControllerDirection.Down || direction == ControllerDirection.Up)
        ) {
            return handleContentVerticalNavigation(current, direction)
        }

        // Settings Lateral Navigation: Always allow seamless handoff between Sidebar (left) and Content (right)
        if (activeZone == ControllerFocusZone.Content) {
            val isCurrentSettingsSidebar = current.groupKey == "settings_sidebar" || current.id.startsWith("settings_sidebar_")
            val isCurrentSettingsContent = current.groupKey == "settings_content" || (current.id.startsWith("settings_") && !isCurrentSettingsSidebar)

            if (isCurrentSettingsSidebar && direction == ControllerDirection.Right) {
                val contentNodes = nodes.values.filter {
                    it.zone == ControllerFocusZone.Content &&
                        it.isVisibleAndValid &&
                        (it.groupKey == "settings_content" || (it.id.startsWith("settings_") && !it.id.startsWith("settings_sidebar_")))
                }
                if (contentNodes.isNotEmpty()) {
                    val target = contentNodes.minByOrNull { it.boundsInRoot.top }
                        ?: contentNodes.first()
                    focusNodeInternal(target, triggerScroll = true, direction = direction)
                    return true
                }
            } else if (isCurrentSettingsContent && direction == ControllerDirection.Left) {
                // First check if there is an inner sibling item to the left within settings_content (e.g. chips, segmented buttons)
                val leftInnerNodes = nodes.values.filter {
                    it.zone == ControllerFocusZone.Content &&
                        it.isVisibleAndValid &&
                        (it.groupKey == "settings_content" || (it.id.startsWith("settings_") && !it.id.startsWith("settings_sidebar_"))) &&
                        it.boundsInRoot.center.x < current.boundsInRoot.center.x - 10f &&
                        abs(it.boundsInRoot.center.y - current.boundsInRoot.center.y) < 36f
                }
                if (leftInnerNodes.isNotEmpty()) {
                    val nextLeft = leftInnerNodes.maxByOrNull { it.boundsInRoot.center.x }
                    if (nextLeft != null) {
                        focusNodeInternal(nextLeft, triggerScroll = true, direction = direction)
                        return true
                    }
                }

                // If at the left edge of content, transition immediately to settings_sidebar
                val sidebarNodes = nodes.values.filter {
                    it.zone == ControllerFocusZone.Content &&
                        it.isVisibleAndValid &&
                        (it.groupKey == "settings_sidebar" || it.id.startsWith("settings_sidebar_"))
                }
                if (sidebarNodes.isNotEmpty()) {
                    val target = sidebarNodes.minByOrNull { abs(it.boundsInRoot.center.y - current.boundsInRoot.center.y) }
                        ?: sidebarNodes.first()
                    focusNodeInternal(target, triggerScroll = false, direction = direction)
                    return true
                }
            }
        }

        // If currently focused on a View All pill and pressing Left, return to that shelf row's rightmost card
        if (activeZone == ControllerFocusZone.Content && current.id.startsWith("view_all_pill_") && direction == ControllerDirection.Left) {
            val contentNodes = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid && !it.id.startsWith("view_all_pill_") }
            val sameRowCard = contentNodes.filter { it.boundsInRoot.top >= current.boundsInRoot.bottom - 10f && it.boundsInRoot.top <= current.boundsInRoot.bottom + 140f }
                .maxByOrNull { it.boundsInRoot.center.x }
            if (sameRowCard != null) {
                focusNodeInternal(sameRowCard, triggerScroll = true, direction = direction)
                return true
            }
        }

        val zoneCandidates = nodes.values.filter {
            it.zone == activeZone && it.isVisibleAndValid && it.id != current.id
        }

        val nextInZone = findBestSpatialCandidate(current, zoneCandidates, direction)
        if (nextInZone != null) {
            val isHorizontal = direction == ControllerDirection.Left || direction == ControllerDirection.Right
            val isSameRow = isHorizontal && (
                (current.rowLazyListState != null && current.rowLazyListState === nextInZone.rowLazyListState) ||
                (current.groupKey != null && current.groupKey == nextInZone.groupKey) ||
                abs(current.boundsInRoot.center.y - nextInZone.boundsInRoot.center.y) < 80f
            )
            focusNodeInternal(nextInZone, triggerScroll = true, suppressVerticalScroll = isSameRow, direction = direction)
            return true
        }

        // If on the rightmost card of a shelf and pressing Right, check if we can focus the row's Explore More / chevron pill
        if (direction == ControllerDirection.Right && !current.id.startsWith("view_all_pill_")) {
            val chevrons = nodes.values.filter {
                it.zone == ControllerFocusZone.Content && it.isVisibleAndValid && it.id.startsWith("view_all_pill_")
            }
            val chevron = chevrons.firstOrNull {
                it.boundsInRoot.bottom <= current.boundsInRoot.top + 35f &&
                    it.boundsInRoot.top >= current.boundsInRoot.top - 180f &&
                    it.boundsInRoot.center.x >= current.boundsInRoot.center.x - 50f
            }
            val rowCardsToRight = nodes.values.filter {
                it.zone == ControllerFocusZone.Content &&
                    it.isVisibleAndValid &&
                    !it.id.startsWith("view_all_pill_") &&
                    it.groupKey == current.groupKey &&
                    it.boundsInRoot.center.x > current.boundsInRoot.center.x + 10f
            }
            if (chevron != null && rowCardsToRight.isEmpty() && (current.rowLazyListState == null || !current.rowLazyListState!!.canScrollForward)) {
                focusNodeInternal(chevron, triggerScroll = true, direction = direction)
                return true
            }
        }

        // If moving Left or Right at the edge of a horizontal LazyRow shelf, scroll the LazyRow to reveal more items!
        if ((direction == ControllerDirection.Right || direction == ControllerDirection.Left) &&
            current.rowLazyListState != null
        ) {
            val rowState = current.rowLazyListState!!
            val canScroll = if (direction == ControllerDirection.Right) {
                rowState.canScrollForward
            } else {
                rowState.canScrollBackward
            }
            if (canScroll) {
                val scope = current.coroutineScope ?: activeCoroutineScope
                if (scope != null) {
                    if (direction == ControllerDirection.Left && (rowState.firstVisibleItemIndex == 0 || rowState.layoutInfo.visibleItemsInfo.firstOrNull()?.index == 0)) {
                        scope.launch {
                            runCatching { rowState.animateScrollToItem(0, 0) }
                        }
                        return true
                    }
                    val groupKey = current.groupKey
                    val layoutInfo = rowState.layoutInfo
                    val viewportWidth = layoutInfo.viewportSize.width.toFloat().takeIf { it > 100f }
                        ?: (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat().takeIf { it > 100f }
                        ?: 1920f
                    val safeLeft = max(layoutInfo.beforeContentPadding.toFloat(), 64f)
                    val safeRight = (viewportWidth - max(layoutInfo.afterContentPadding.toFloat(), 64f)).coerceAtLeast(safeLeft + 120f)
                    val unclippedCurrent = if (current.unclippedBoundsInRoot.width > 2f) current.unclippedBoundsInRoot else current.boundsInRoot

                    val scrollDelta = if (direction == ControllerDirection.Right) {
                        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                        val cardWidth = lastVisible?.size?.toFloat()
                            ?: unclippedCurrent.width.takeIf { it > 50f }
                            ?: 240f
                        val gap = 16f
                        val lastEnd = unclippedCurrent.right.coerceAtLeast(safeRight)
                        val overflow = (lastEnd - safeRight).coerceAtLeast(0f)
                        (overflow + cardWidth + gap + 24f)
                    } else {
                        val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
                        val cardWidth = firstVisible?.size?.toFloat()
                            ?: unclippedCurrent.width.takeIf { it > 50f }
                            ?: 240f
                        val gap = 16f
                        val firstStart = unclippedCurrent.left.coerceAtMost(safeLeft)
                        val underflow = (safeLeft - firstStart).coerceAtLeast(0f)
                        -(underflow + cardWidth + gap + 24f)
                    }

                    activeHorizontalScrollJob?.cancel()
                    activeHorizontalScrollJob = scope.launch {
                        runCatching { rowState.animateScrollBy(scrollDelta, tween(170, easing = SnappyEaseOut)) }
                        for (attempt in 0..6) {
                            delay(30)
                            val refreshed = nodes.values.filter {
                                it.zone == activeZone &&
                                    it.isVisibleAndValid &&
                                    it.id != current.id &&
                                    (groupKey == null || it.groupKey == groupKey)
                            }
                            val newlyVisible = findBestSpatialCandidate(current, refreshed, direction)
                            if (newlyVisible != null) {
                                focusNodeInternal(newlyVisible, triggerScroll = true, suppressVerticalScroll = true, direction = direction)
                                break
                            }
                        }
                    }
                    return true
                }
            }
        }

        // Zone transitions between Navigation (Sidebar / TopBar) and Content
        if (activeZone == ControllerFocusZone.Content) {
            if (direction == ControllerDirection.Left) {
                // At the left edge of the content, hand focus back to the navigation rail. This is
                // the desktop counterpart of a sidebar: Left leaves a poster row and selects the
                // rail item closest to the current card.
                val navigationNodes = nodes.values.filter {
                    it.zone == ControllerFocusZone.Navigation && it.isVisibleAndValid
                }
                if (navigationNodes.isNotEmpty()) {
                    val target = navigationNodes.firstOrNull { it.isDefaultFocus }
                        ?: navigationNodes.minByOrNull {
                            abs(it.boundsInRoot.center.y - current.boundsInRoot.center.y)
                        }
                    if (target != null) {
                        manualSidebarOpen = true
                        focusNodeInternal(target, triggerScroll = false, direction = direction)
                        return true
                    }
                }
                return true
            } else if (direction == ControllerDirection.Up) {
                // First check if vertical list can scroll up to reveal upper content/hero
                val activeScroll = getActiveScrollable()
                val listState = activeScroll?.lazyListState
                val scrollState = activeScroll?.scrollState
                val canScrollUp = (listState != null && listState.canScrollBackward) ||
                    (scrollState != null && scrollState.canScrollBackward)
                if (canScrollUp) {
                    val scope = activeScroll?.scope ?: activeCoroutineScope ?: current.coroutineScope
                    if (scope != null) {
                        scope.launch {
                            if (listState != null) {
                                runCatching { listState.animateScrollBy(-360f, tween(160)) }
                            } else if (scrollState != null) {
                                runCatching { scrollState.animateScrollBy(-360f, tween(160)) }
                            }
                            delay(180)
                            val refreshed = nodes.values.filter {
                                it.zone == ControllerFocusZone.Content && it.isVisibleAndValid && it.id != current.id
                            }
                            val candidate = findBestSpatialCandidate(current, refreshed, ControllerDirection.Up)
                            if (candidate != null) {
                                focusNodeInternal(candidate, triggerScroll = true)
                            }
                        }
                        return true
                    }
                }
                // At the very top of Content: clamp and remain in Content zone
                return true
            } else if (direction == ControllerDirection.Down) {
                // Scroll vertical container down to compose the next shelf/row
                val activeScroll = getActiveScrollable()
                val listState = activeScroll?.lazyListState
                val scrollState = activeScroll?.scrollState
                val canScrollDown = (listState != null && listState.canScrollForward) ||
                    (scrollState != null && scrollState.canScrollForward)
                if (canScrollDown) {
                    val scope = activeScroll?.scope ?: activeCoroutineScope ?: current.coroutineScope
                    if (scope != null) {
                        scope.launch {
                            if (listState != null) {
                                runCatching { listState.animateScrollBy(360f, tween(160)) }
                            } else if (scrollState != null) {
                                runCatching { scrollState.animateScrollBy(360f, tween(160)) }
                            }
                            delay(180)
                            val refreshed = nodes.values.filter {
                                it.zone == ControllerFocusZone.Content && it.isVisibleAndValid && it.id != current.id
                            }
                            val candidate = findBestSpatialCandidate(current, refreshed, ControllerDirection.Down)
                            if (candidate != null) {
                                focusNodeInternal(candidate, triggerScroll = true)
                            }
                        }
                        return true
                    }
                }
            }
        } else if (activeZone == ControllerFocusZone.Navigation) {
            val isTopBar = current.groupKey == "top_nav" || current.id.startsWith("top_nav_")
            if (direction == ControllerDirection.Down || (!isTopBar && direction == ControllerDirection.Right)) {
                val contentNodes = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
                if (contentNodes.isNotEmpty()) {
                    val rememberedId = lastFocusedByZone[ControllerFocusZone.Content]
                    val target = rememberedId?.let { id -> contentNodes.firstOrNull { it.id == id } }
                        ?: contentNodes.filter { it.isDefaultFocus }
                            .minWithOrNull(
                                compareBy<ControllerFocusNode> { (it.boundsInRoot.top / 32f).toInt() }
                                    .thenBy { it.boundsInRoot.left },
                            )
                        ?: contentNodes.minWithOrNull(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
                    if (target != null) {
                        focusNodeInternal(target, triggerScroll = true)
                        return true
                    }
                }
            }
            // Left/Up at the rail edge stay in the rail instead of leaking to Compose's focus
            // search, which would select an unrelated focusable.
            if (direction == ControllerDirection.Left || direction == ControllerDirection.Up) {
                return true
            }
        }

        // At the edge of a content row (e.g. the last poster of a shelf) consume horizontal input
        // instead of leaking to Compose's focus search: the fallback moved an invisible Compose
        // focus and made the spatial ring blink on and off at the end of the row.
        if (activeZone == ControllerFocusZone.Content &&
            (direction == ControllerDirection.Left || direction == ControllerDirection.Right)
        ) {
            return true
        }

        return false
    }

    private fun findBestSpatialCandidate(
        current: ControllerFocusNode,
        candidates: List<ControllerFocusNode>,
        direction: ControllerDirection,
    ): ControllerFocusNode? {
        val from = if (current.rowLazyListState != null && current.unclippedBoundsInRoot.width > 2f) {
            current.unclippedBoundsInRoot
        } else {
            current.boundsInRoot
        }
        val fromCenter = from.center

        if (direction == ControllerDirection.Left || direction == ControllerDirection.Right) {
            // 1. First look for candidates in the same groupKey or vertically overlapping row
            val sameRowCandidates = candidates.filter { candidate ->
                val to = if (candidate.rowLazyListState != null && candidate.unclippedBoundsInRoot.width > 2f) {
                    candidate.unclippedBoundsInRoot
                } else {
                    candidate.boundsInRoot
                }
                val toCenter = to.center
                val isCorrectHorizontalDir = if (direction == ControllerDirection.Right) {
                    toCenter.x > fromCenter.x + 8f
                } else {
                    toCenter.x < fromCenter.x - 8f
                }
                if (!isCorrectHorizontalDir) return@filter false

                val sameGroup = current.groupKey != null && current.groupKey == candidate.groupKey &&
                    abs(toCenter.y - fromCenter.y) < max(from.height, to.height) * 0.85f
                val verticalOverlap = min(from.bottom, to.bottom) - max(from.top, to.top)
                val minHeight = min(from.height, to.height).coerceAtLeast(1f)
                sameGroup || (verticalOverlap / minHeight) >= 0.25f || abs(toCenter.y - fromCenter.y) <= 36f
            }

            if (sameRowCandidates.isNotEmpty()) {
                return sameRowCandidates.minByOrNull { candidate ->
                    val to = if (candidate.rowLazyListState != null && candidate.unclippedBoundsInRoot.width > 2f) {
                        candidate.unclippedBoundsInRoot
                    } else {
                        candidate.boundsInRoot
                    }
                    val toCenter = to.center
                    val dx = abs(toCenter.x - fromCenter.x)
                    val dy = abs(toCenter.y - fromCenter.y)
                    val groupBonus = if (current.groupKey != null && current.groupKey == candidate.groupKey) -50f else 0f
                    dx + dy * 3.5f + groupBonus
                }
            }
            return null
        }

        // Up or Down navigation
        val directionalCandidates = candidates.filter { candidate ->
            val isChevron = candidate.id.startsWith("view_all_pill_")
            if (isChevron) {
                // Chevrons are ONLY reachable when direction is Up AND current is on the far-right (last) card of that row
                if (direction == ControllerDirection.Up) {
                    val isCurrentChevron = current.id.startsWith("view_all_pill_")
                    val isFarRightCard = !isCurrentChevron && (
                        (current.rowItemIndex >= 0 && current.rowTotalCount > 0 && current.rowItemIndex == current.rowTotalCount - 1) ||
                        candidates.none { other ->
                            other.groupKey != null && other.groupKey == current.groupKey &&
                                other.boundsInRoot.center.x > fromCenter.x + 10f
                        }
                    )
                    val to = candidate.boundsInRoot
                    val isDirectlyAbove = to.center.y < fromCenter.y - 14f && abs(to.center.y - fromCenter.y) < 320f
                    if (!isFarRightCard || !isDirectlyAbove) return@filter false
                } else {
                    // Down navigation never targets a chevron
                    return@filter false
                }
            }

            // If current IS a chevron, pressing Down must return straight back to that row's cards
            if (current.id.startsWith("view_all_pill_") && direction == ControllerDirection.Down) {
                if (isChevron) return@filter false
            }

            val to = candidate.boundsInRoot
            val toCenter = to.center
            if (direction == ControllerDirection.Down) {
                toCenter.y > fromCenter.y + 14f && !(current.groupKey != null && current.groupKey == candidate.groupKey && abs(toCenter.y - fromCenter.y) < from.height * 0.6f)
            } else {
                toCenter.y < fromCenter.y - 14f && !(current.groupKey != null && current.groupKey == candidate.groupKey && abs(toCenter.y - fromCenter.y) < from.height * 0.6f)
            }
        }

        if (directionalCandidates.isEmpty()) return null

        // Find the closest vertical row/band in that direction first
        val nearestVerticalDistance = directionalCandidates.minOf { candidate ->
            val to = candidate.boundsInRoot
            if (direction == ControllerDirection.Down) {
                max(0f, to.top - from.bottom) + abs(to.center.y - fromCenter.y) * 0.35f
            } else {
                max(0f, from.top - to.bottom) + abs(fromCenter.y - to.center.y) * 0.35f
            }
        }

        // Consider candidates in the nearest vertical band (within a reasonable vertical tolerance)
        val bandTolerance = 110f
        val bandCandidates = directionalCandidates.filter { candidate ->
            val to = candidate.boundsInRoot
            val vDist = if (direction == ControllerDirection.Down) {
                max(0f, to.top - from.bottom) + abs(to.center.y - fromCenter.y) * 0.35f
            } else {
                max(0f, from.top - to.bottom) + abs(fromCenter.y - to.center.y) * 0.35f
            }
            vDist <= nearestVerticalDistance + bandTolerance
        }

        // If the target band belongs to a groupKey where we have a remembered focused node (and it's a horizontal shelf, not a multi-row grid), prefer the remembered node or closest X
        val bestBandCandidate = bandCandidates.minByOrNull { candidate ->
            val to = candidate.boundsInRoot
            val dy = abs(to.center.y - fromCenter.y)
            val dx = abs(to.center.x - fromCenter.x)
            val horizontalOverlap = max(0f, min(from.right, to.right) - max(from.left, to.left))
            val overlapBonus = if (horizontalOverlap > 0f) -60f else 0f
            dy * 2.2f + dx * 0.85f + overlapBonus
        }

        return bestBandCandidate
    }
}

private var autoControllerNodeCounter = 0L

/**
 * Registers global/screen-level controller action handlers for the active composition scope.
 */
@Composable
fun RegisterControllerHandler(
    key: String = "",
    screenContext: ControllerScreenContext? = null,
    onBack: (() -> Boolean)? = null,
    onBumperLeft: (() -> Boolean)? = null,
    onBumperRight: (() -> Boolean)? = null,
    onShoulderLeft: (() -> Boolean)? = null,
    onShoulderRight: (() -> Boolean)? = null,
    onTriggerLeft: (() -> Boolean)? = null,
    onTriggerRight: (() -> Boolean)? = null,
    onActionX: (() -> Boolean)? = null,
    onActionY: (() -> Boolean)? = null,
    onSecondaryAction: (() -> Boolean)? = null,
    onTertiaryAction: (() -> Boolean)? = null,
    onViewButton: (() -> Boolean)? = null,
    onMenuButton: (() -> Boolean)? = null,
    onViewAction: (() -> Boolean)? = null,
    onMenuAction: (() -> Boolean)? = null,
    onEnterNavigationZone: (() -> Unit)? = null,
    onExitNavigationZone: (() -> Unit)? = null,
) {
    val effectiveKey = remember(key) {
        key.ifEmpty { "controller_handler_${++autoControllerNodeCounter}" }
    }
    val latestBack by rememberUpdatedState(onBack)
    val latestLB by rememberUpdatedState(onBumperLeft ?: onShoulderLeft)
    val latestRB by rememberUpdatedState(onBumperRight ?: onShoulderRight)
    val latestLT by rememberUpdatedState(onTriggerLeft)
    val latestRT by rememberUpdatedState(onTriggerRight)
    val latestX by rememberUpdatedState(onActionX ?: onSecondaryAction)
    val latestY by rememberUpdatedState(onActionY ?: onTertiaryAction)
    val latestView by rememberUpdatedState(onViewButton ?: onViewAction)
    val latestMenu by rememberUpdatedState(onMenuButton ?: onMenuAction)
    val latestEnterNav by rememberUpdatedState(onEnterNavigationZone)
    val latestExitNav by rememberUpdatedState(onExitNavigationZone)

    LaunchedEffect(screenContext) {
        if (screenContext != null) {
            ControllerManager.updateScreenContext(screenContext)
        }
    }

    DisposableEffect(effectiveKey) {
        ControllerFocusEngine.registerGlobalHandlers(
            key = effectiveKey,
            handlers = ControllerGlobalHandlers(
                onBack = { latestBack?.invoke() == true },
                onBumperLeft = { latestLB?.invoke() == true },
                onBumperRight = { latestRB?.invoke() == true },
                onTriggerLeft = { latestLT?.invoke() == true },
                onTriggerRight = { latestRT?.invoke() == true },
                onActionX = { latestX?.invoke() == true },
                onActionY = { latestY?.invoke() == true },
                onViewButton = { latestView?.invoke() == true },
                onMenuButton = { latestMenu?.invoke() == true },
                onEnterNavigationZone = { latestEnterNav?.invoke() },
                onExitNavigationZone = { latestExitNav?.invoke() },
            ),
        )
        onDispose {
            ControllerFocusEngine.unregisterGlobalHandlers(effectiveKey)
        }
    }
}

/**
 * Registers a vertical LazyListState or ScrollState so the controller spatial engine
 * can automatically scroll shelves/rows into view and handle LT/RT page jumps and RS scrolling.
 *
 * The returned modifier reports the container's on-screen viewport to the engine so scroll
 * centring accounts for headers/tab rows above the list (otherwise focused rows could land
 * behind the header and appear to lose focus).
 */
@Composable
fun RegisterControllerVerticalScroll(
    lazyListState: LazyListState? = null,
    scrollState: ScrollState? = null,
    lazyGridState: LazyGridState? = null,
): Modifier {
    val isScreenActive = LocalScreenActive.current
    val scope = rememberCoroutineScope()
    var focusVisibilityJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(lazyListState, scrollState, lazyGridState, isScreenActive) {
        if (isScreenActive) {
            ControllerFocusEngine.registerVerticalScrollable(
                lazyListState = lazyListState,
                scrollState = scrollState,
                lazyGridState = lazyGridState,
                scope = scope,
            )
        }
        onDispose {
            ControllerFocusEngine.unregisterVerticalScrollable(
                lazyListState = lazyListState,
                scrollState = scrollState,
                lazyGridState = lazyGridState,
            )
        }
    }
    LaunchedEffect(lazyListState, scrollState, lazyGridState, isScreenActive) {
        if (!isScreenActive) return@LaunchedEffect
        var lastIndex = lazyListState?.firstVisibleItemIndex ?: lazyGridState?.firstVisibleItemIndex ?: 0
        var lastOffset = lazyListState?.firstVisibleItemScrollOffset
            ?: lazyGridState?.firstVisibleItemScrollOffset
            ?: scrollState?.value
            ?: 0
        snapshotFlow {
            val idx = lazyListState?.firstVisibleItemIndex ?: lazyGridState?.firstVisibleItemIndex ?: 0
            val off = lazyListState?.firstVisibleItemScrollOffset
                ?: lazyGridState?.firstVisibleItemScrollOffset
                ?: scrollState?.value
                ?: 0
            idx to off
        }.collect { (idx, off) ->
            val topThreshold = if (ControllerFocusEngine.isHomeTabActive) 16 else 95
            if (idx == 0 && off <= topThreshold) {
                ControllerFocusEngine.setTopBarVisibleByScroll(true)
            } else if (idx > lastIndex || (idx == lastIndex && off > lastOffset + 8)) {
                ControllerFocusEngine.setTopBarVisibleByScroll(false)
            } else if (idx < lastIndex || (idx == lastIndex && off < lastOffset - 8)) {
                ControllerFocusEngine.setTopBarVisibleByScroll(true)
            }
            // A lazy container can keep the focused item composed after it leaves the viewport,
            // which hides its ring while the list keeps scrolling. Once scrolling settles, pull the
            // selection back to the nearest visible item.
            if (ControllerManager.isNavigationActive.value) {
                focusVisibilityJob?.cancel()
                focusVisibilityJob = scope.launch {
                    delay(260)
                    ControllerFocusEngine.ensureFocusWithinActiveViewport()
                }
            }
            lastIndex = idx
            lastOffset = off
        }
    }
    return Modifier.onGloballyPositioned { coordinates ->
        if (!isScreenActive || !coordinates.isAttached) return@onGloballyPositioned
        val bounds = coordinates.boundsInRoot()
        if (bounds.height <= 0f) return@onGloballyPositioned
        ControllerFocusEngine.updateScrollableViewport(
            lazyListState = lazyListState,
            scrollState = scrollState,
            lazyGridState = lazyGridState,
            topInRoot = bounds.top,
            height = bounds.height,
            leftInRoot = bounds.left,
            width = bounds.width,
        )
    }
}

/**
 * Makes any UI element focusable and navigable by the Windows 11 Controller Spatial Navigation Engine,
 * automatically scrolling it into view when focused and drawing a high-contrast visual focus ring.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.controllerFocusable(
    id: String = "",
    zone: ControllerFocusZone? = null,
    groupKey: String? = null,
    enabled: Boolean = true,
    isDefaultFocus: Boolean = false,
    isTextInput: Boolean = false,
    shape: Shape? = null,
    cornerRadius: Dp = 12.dp,
    focusScale: Float = 1.0f,
    scaleOnFocus: Float? = null,
    useHaloFocusRing: Boolean = false,
    hideFocusRing: Boolean = false,
    selectLabel: String = "Select",
    actionLabel: String? = null,
    secondaryActionLabel: String? = null,
    tertiaryActionLabel: String? = null,
    onFocused: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null,
    onTertiaryAction: (() -> Unit)? = null,
    onMoveUp: (() -> Boolean)? = null,
    onMoveDown: (() -> Boolean)? = null,
    onMoveLeft: (() -> Boolean)? = null,
    onMoveRight: (() -> Boolean)? = null,
    /** Appends the ambient group key to [id] so the same card in two rows gets distinct ids. */
    dedupeByGroup: Boolean = false,
    onSelect: () -> Unit,
): Modifier = composed {
    val ambientZone = LocalControllerFocusZone.current
    val ambientGroupKey = LocalControllerGroupKey.current
    val ambientRowState = LocalControllerRowLazyListState.current
    val effectiveId = remember(id, ambientGroupKey, dedupeByGroup) {
        val base = id.ifEmpty { "ctrl_node_${++autoControllerNodeCounter}" }
        if (dedupeByGroup && !ambientGroupKey.isNullOrEmpty()) "$base@$ambientGroupKey" else base
    }
    val effectiveSelectLabel = actionLabel ?: selectLabel
    val effectiveFocusScale = scaleOnFocus ?: focusScale
    val isControllerActive by ControllerManager.isNavigationActive.collectAsState()
    val isScreenActive = LocalScreenActive.current
    val effectiveEnabled = enabled && isScreenActive
    val focusedNodeId by ControllerFocusEngine.focusedNodeId.collectAsState()
    val isFocused = effectiveEnabled && isControllerActive && focusedNodeId == effectiveId

    val effectiveZone = zone ?: ambientZone
    val effectiveGroupKey = groupKey ?: ambientGroupKey

    val coroutineScope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(isFocused) {
        if (isFocused) {
            runCatching { bringIntoViewRequester.bringIntoView() }
        }
    }

    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnSecondary by rememberUpdatedState(onSecondaryAction)
    val latestOnTertiary by rememberUpdatedState(onTertiaryAction)
    val latestOnFocused by rememberUpdatedState(onFocused)
    val latestMoveUp by rememberUpdatedState(onMoveUp)
    val latestMoveDown by rememberUpdatedState(onMoveDown)
    val latestMoveLeft by rememberUpdatedState(onMoveLeft)
    val latestMoveRight by rememberUpdatedState(onMoveRight)

    var latestBounds by remember { mutableStateOf(Rect.Zero) }
    var latestUnclippedBounds by remember { mutableStateOf(Rect.Zero) }

    val node = remember(effectiveId) {
        ControllerFocusNode(
            id = effectiveId,
            zone = effectiveZone,
            groupKey = effectiveGroupKey,
        )
    }

    node.zone = effectiveZone
    node.groupKey = effectiveGroupKey
    node.enabled = effectiveEnabled
    node.isDefaultFocus = isDefaultFocus
    node.isTextInput = isTextInput
    node.selectLabel = effectiveSelectLabel
    node.secondaryActionLabel = secondaryActionLabel
    node.tertiaryActionLabel = tertiaryActionLabel
    node.onSelect = { latestOnSelect() }
    node.onSecondaryAction = latestOnSecondary
    node.onTertiaryAction = latestOnTertiary
    node.onMoveUp = latestMoveUp
    node.onMoveDown = latestMoveDown
    node.onMoveLeft = latestMoveLeft
    node.onMoveRight = latestMoveRight
    node.rowLazyListState = ambientRowState
    node.coroutineScope = coroutineScope
    node.onFocusedWithOption = { _ ->
        latestOnFocused?.invoke()
    }
    node.onFocused = {
        latestOnFocused?.invoke()
    }

    // Lets the engine hand focus to Compose from this element (see ControllerFocusEngine.yieldToCompose),
    // and take it back when Compose focus returns here. The requester targets the focusable the caller
    // chains after this modifier (clickable, Surface onClick).
    val composeFocusRequester = remember { FocusRequester() }
    node.requestComposeFocus = { composeFocusRequester.requestFocus(FocusDirection.Enter) }

    DisposableEffect(effectiveId, effectiveEnabled) {
        if (effectiveEnabled) {
            ControllerFocusEngine.upsertNode(node)
        } else {
            ControllerFocusEngine.removeNode(effectiveId)
        }
        onDispose {
            ControllerFocusEngine.removeNode(effectiveId)
        }
    }

    LaunchedEffect(
        effectiveId,
        effectiveEnabled,
        effectiveZone,
        effectiveGroupKey,
        effectiveSelectLabel,
        secondaryActionLabel,
        tertiaryActionLabel,
        onSecondaryAction != null,
        onTertiaryAction != null,
    ) {
        if (effectiveEnabled) {
            ControllerFocusEngine.upsertNode(node)
        }
    }

    val focusedSnapshot by ControllerFocusEngine.focusedNodeSnapshot.collectAsState()
    val isSiblingActiveInGroup = !isFocused && isControllerActive && effectiveGroupKey != null &&
        focusedSnapshot?.groupKey == effectiveGroupKey
    val targetAlpha = if (isSiblingActiveInGroup) 0.82f else 1f
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 150),
        label = "controller_sibling_alpha",
    )

    // The ring itself is drawn once, above everything, by FocusRingOverlay; the focused node only
    // reports where it is. Its bounds are scaled the way its graphicsLayer scales it.
    val ringOwner = remember { Any() }
    val ringShape = shape ?: RoundedCornerShape(cornerRadius)
    val showsRing = isFocused && !hideFocusRing
    val latestShowsRing by rememberUpdatedState(showsRing)
    val latestRingShape by rememberUpdatedState(ringShape)
    fun reportRing(unclipped: Rect) {
        if (unclipped.width <= 0f || unclipped.height <= 0f) return
        val c = unclipped.center
        val halfW = unclipped.width * effectiveFocusScale / 2f
        val halfH = unclipped.height * effectiveFocusScale / 2f
        FocusRingRegistry.reportEngine(
            FocusRingTarget(ringOwner, Rect(c.x - halfW, c.y - halfH, c.x + halfW, c.y + halfH), latestRingShape),
        )
    }
    LaunchedEffect(showsRing, ringShape) {
        if (showsRing) reportRing(latestUnclippedBounds) else FocusRingRegistry.clearEngine(ringOwner)
    }
    DisposableEffect(ringOwner) {
        onDispose { FocusRingRegistry.clearEngine(ringOwner) }
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) effectiveFocusScale else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "controller_focus_scale",
    )

    this
        .bringIntoViewRequester(bringIntoViewRequester)
        .onGloballyPositioned { coordinates ->
            val bounds = if (coordinates.isAttached) coordinates.boundsInRoot() else Rect.Zero
            val unclippedBounds = if (coordinates.isAttached) {
                val pos = coordinates.positionInRoot()
                Rect(pos.x, pos.y, pos.x + coordinates.size.width, pos.y + coordinates.size.height)
            } else {
                Rect.Zero
            }
            if (bounds != latestBounds || unclippedBounds != latestUnclippedBounds) {
                latestBounds = bounds
                latestUnclippedBounds = unclippedBounds
                node.boundsInRoot = bounds
                node.unclippedBoundsInRoot = unclippedBounds
                if (latestShowsRing) reportRing(unclippedBounds)
                if (effectiveEnabled) {
                    ControllerFocusEngine.upsertNode(node)
                }
            }
        }
        .zIndex(if (isFocused) 15f else 0f)
        .graphicsLayer {
            if (scale != 1f) {
                scaleX = scale
                scaleY = scale
            }
            if (animatedAlpha != 1f) {
                alpha = animatedAlpha
            }
        }
        .focusRequester(composeFocusRequester)
        .onFocusChanged { state -> ControllerFocusEngine.onComposeFocusChanged(node, state.isFocused) }
}

/**
 * Convenience check for whether a specific controller node currently holds active spatial focus.
 */
@Composable
fun isControllerNodeFocused(id: String): Boolean {
    val isControllerActive by ControllerManager.isNavigationActive.collectAsState()
    val focusedNodeId by ControllerFocusEngine.focusedNodeId.collectAsState()
    return isControllerActive && focusedNodeId == id
}


