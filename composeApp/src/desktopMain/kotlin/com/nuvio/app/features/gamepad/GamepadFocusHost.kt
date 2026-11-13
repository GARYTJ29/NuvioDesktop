package com.nuvio.app.features.gamepad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.focus.getFocusedRect
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.unit.dp
import com.nuvio.app.AppScreenTab
import com.nuvio.app.awtScale
import com.nuvio.app.core.input.AppNavigationBridge
import com.nuvio.app.core.input.NavigationDirection
import com.nuvio.app.features.player.desktop.toggleDesktopAppFullscreen
import com.nuvio.app.postPreciseWheel
import java.awt.AWTEvent
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

// The shell scrolled by three steps of 30px when the d-pad found nothing further
// in its direction.
private const val EdgeScrollPx = 90f

// Stick ticks closer together than this belong to one scroll gesture. The
// poller sends one every ~8ms while the stick is deflected.
private const val ScrollGestureGapMs = 250L

/**
 * Where the wheel events of the current stick gesture go, in host pixels. It
 * stays put for the whole gesture: the focused card moves with the content, and
 * following it would walk the pointer off the row (or the window) and strand
 * the scroll.
 */
private class ScrollAnchor {
    var point: Offset? = null
    var lastAt: Long = 0L
}

/**
 * Drives the app's own Compose focus from the pad, the way the shell's spatial
 * navigation drove the web UI: d-pad/left stick move focus, A activates (or
 * opens the on-screen keyboard on a text field), B goes back, X/Y jump to
 * Search/Home, LB/RB walk the tabs, the right stick scrolls and L3 toggles
 * fullscreen. Draws a focus ring while a pad is connected.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GamepadFocusHost(window: Window, content: @Composable () -> Unit) {
    val focusManager = LocalFocusManager.current
    val settings by DesktopControllerSettings.state.collectAsState()
    val connected by GamepadRouter.connected.collectAsState()
    var hostHasFocus by remember { mutableStateOf(false) }
    // The focused item's bounds in this host's coordinates, read every frame
    // while a pad is connected so the ring follows scrolling lists.
    val focusProbe = remember { FocusRectProbe() }
    var focusedBounds by remember { mutableStateOf<Rect?>(null) }
    var textInputSessions by remember { mutableIntStateOf(0) }
    var keyboardVisible by remember { mutableStateOf(false) }

    val latestSettings = rememberUpdatedState(settings)
    val latestFocusedBounds = rememberUpdatedState(focusedBounds)
    val latestHostHasFocus = rememberUpdatedState(hostHasFocus)
    val latestTextInput = rememberUpdatedState(textInputSessions)

    // A text field holds an input session for as long as it is focused, which
    // is how a press of A knows it is on one.
    val textInputInterceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            textInputSessions++
            try {
                nextHandler.startInputMethod(request)
            } finally {
                textInputSessions--
            }
        }
    }

    LaunchedEffect(textInputSessions) {
        if (textInputSessions == 0) keyboardVisible = false
    }
    LaunchedEffect(connected, settings.enabled) {
        if (!connected || !settings.enabled) {
            focusedBounds = null
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            val rect = focusProbe.focusedRect()
            if (rect != focusedBounds) focusedBounds = rect
        }
    }
    LaunchedEffect(keyboardVisible) {
        GamepadRouter.isKeyboardOpen = keyboardVisible
    }

    val scrollAnchor = remember { ScrollAnchor() }

    fun scrollBy(dx: Float, dy: Float) {
        val component = window.focusOwner ?: (window as? javax.swing.RootPaneContainer)?.contentPane ?: return
        val scale = component.awtScale.toFloat()
        // The host fills the window's content, so its pixels map straight onto
        // the content component.
        val visible = Rect(0f, 0f, component.width * scale, component.height * scale)
        val now = System.currentTimeMillis()
        val previous = scrollAnchor.point
        val anchor = if (previous != null && now - scrollAnchor.lastAt < ScrollGestureGapMs) {
            previous
        } else {
            // A new gesture scrolls whatever is under the focused item, if that
            // is still on screen; otherwise wherever the last gesture scrolled,
            // or the middle of the window.
            val center = latestFocusedBounds.value?.center
            when {
                center != null && visible.contains(center) -> center
                previous != null && visible.contains(previous) -> previous
                else -> visible.center
            }
        }
        scrollAnchor.point = anchor
        scrollAnchor.lastAt = now
        val local = Point((anchor.x / scale).toInt(), (anchor.y / scale).toInt())
        SwingUtilities.convertPointToScreen(local, component)
        if (dx != 0f) postPreciseWheel(component, local, (dx * scale).toDouble(), horizontal = true, modifiers = 0)
        if (dy != 0f) postPreciseWheel(component, local, (dy * scale).toDouble(), horizontal = false, modifiers = 0)
    }

    fun navigate(button: GamepadButton) {
        val direction = when (button) {
            GamepadButton.UP -> FocusDirection.Up
            GamepadButton.DOWN -> FocusDirection.Down
            GamepadButton.LEFT -> FocusDirection.Left
            else -> FocusDirection.Right
        }
        if (!latestSettings.value.focusNavigation) {
            when (button) {
                GamepadButton.UP -> scrollBy(0f, -EdgeScrollPx)
                GamepadButton.DOWN -> scrollBy(0f, EdgeScrollPx)
                else -> Unit
            }
            return
        }
        if (latestHostHasFocus.value) {
            val navigationDirection = when (button) {
                GamepadButton.UP -> NavigationDirection.Up
                GamepadButton.DOWN -> NavigationDirection.Down
                GamepadButton.LEFT -> NavigationDirection.Left
                else -> NavigationDirection.Right
            }
            if (AppNavigationBridge.dispatchDirectionalInput(navigationDirection)) return
            val moved = focusManager.moveFocus(direction)
            if (!moved) {
                when (button) {
                    GamepadButton.UP -> scrollBy(0f, -EdgeScrollPx)
                    GamepadButton.DOWN -> scrollBy(0f, EdgeScrollPx)
                    else -> Unit
                }
            }
        } else {
            // Nothing focused in the main layer: either a dialog has the focus,
            // where Tab walks its controls, or nothing does, where Tab seeds it.
            AwtKeys.tab(backwards = button == GamepadButton.UP || button == GamepadButton.LEFT)
        }
    }

    DisposableEffect(window) {
        val handler = object : GamepadInputHandler {
            override fun onPress(press: GamepadPress): Boolean {
                when (press.button) {
                    GamepadButton.UP, GamepadButton.DOWN,
                    GamepadButton.LEFT, GamepadButton.RIGHT -> navigate(press.button)
                    GamepadButton.A -> {
                        if (latestTextInput.value > 0 && latestSettings.value.virtualKeyboard) {
                            keyboardVisible = true
                        } else {
                            AwtKeys.enter()
                        }
                    }
                    GamepadButton.B, GamepadButton.BACK -> AwtKeys.escape()
                    GamepadButton.X -> AppNavigationBridge.requestTab(AppScreenTab.Search)
                    GamepadButton.Y, GamepadButton.START -> AppNavigationBridge.requestTab(AppScreenTab.Home)
                    GamepadButton.RB -> AppNavigationBridge.cycleTab(1)
                    GamepadButton.L3 -> toggleDesktopAppFullscreen(window)
                    else -> return false
                }
                return true
            }

            override fun onScroll(dx: Float, dy: Float): Boolean {
                scrollBy(dx, dy)
                return true
            }
        }
        GamepadRouter.browseHandler = handler
        GamepadPoller.start()
        // Hand the UI back to the mouse once it really moves. Compare screen
        // positions: Windows also reports a move when a window resizes or opens
        // under a still pointer.
        var lastPointer: Point? = null
        val mouseListener = AWTEventListener { event ->
            val mouse = event as? MouseEvent ?: return@AWTEventListener
            if (mouse.id != MouseEvent.MOUSE_MOVED && mouse.id != MouseEvent.MOUSE_DRAGGED) return@AWTEventListener
            val position = mouse.locationOnScreen
            val previous = lastPointer
            lastPointer = position
            if (previous != null && previous != position) AppNavigationBridge.reportDirectionalInput(false)
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(mouseListener, AWTEvent.MOUSE_MOTION_EVENT_MASK)
        onDispose {
            Toolkit.getDefaultToolkit().removeAWTEventListener(mouseListener)
            if (GamepadRouter.browseHandler === handler) GamepadRouter.browseHandler = null
        }
    }

    // A new screen starts without the old one's held direction running away.
    val currentTab by AppNavigationBridge.currentTab.collectAsState()
    val onRootTabs by AppNavigationBridge.isOnRootTabs.collectAsState()
    LaunchedEffect(currentTab, onRootTabs) { GamepadPoller.restartRepeats() }

    // This sits outside NuvioTheme, and the app is dark throughout.
    val ringColor = Color.White
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { hostHasFocus = it.hasFocus }
            .then(FocusRectProbeElement(focusProbe)),
    ) {
        InterceptPlatformTextInput(textInputInterceptor) {
            content()
        }
        val ring = focusedBounds
        if (connected && settings.enabled && settings.focusNavigation && hostHasFocus && ring != null) {
            FocusRing(ring, ringColor)
        }
        if (keyboardVisible) {
            GamepadKeyboard(
                fieldBounds = focusedBounds,
                hostOrigin = Offset.Zero,
                onClose = { keyboardVisible = false },
            )
        }
    }
}

/** Reads the focused item's rect, relative to the node it is attached through. */
internal class FocusRectProbe {
    internal var node: FocusRectProbeNode? = null

    fun focusedRect(): Rect? = node?.let { probe ->
        runCatching { probe.target.getFocusedRect() }.getOrNull()
    }
}

// A focus target that never takes focus itself: it groups the app's focusables
// so that getFocusedRect can report whichever of them holds focus.
internal class FocusRectProbeNode(private var probe: FocusRectProbe) : DelegatingNode() {
    val target: FocusTargetModifierNode = delegate(FocusTargetModifierNode(focusability = Focusability.Never))

    override fun onAttach() {
        probe.node = this
    }

    override fun onDetach() {
        if (probe.node === this) probe.node = null
    }

    fun update(next: FocusRectProbe) {
        if (next === probe) return
        if (probe.node === this) probe.node = null
        probe = next
        if (isAttached) probe.node = this
    }
}

private data class FocusRectProbeElement(val probe: FocusRectProbe) : ModifierNodeElement<FocusRectProbeNode>() {
    override fun create(): FocusRectProbeNode = FocusRectProbeNode(probe)

    override fun update(node: FocusRectProbeNode) = node.update(probe)
}

@Composable
private fun FocusRing(bounds: Rect, color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(bounds.left + inset, bounds.top + inset),
            size = Size(
                (bounds.width - stroke).coerceAtLeast(0f),
                (bounds.height - stroke).coerceAtLeast(0f),
            ),
            cornerRadius = CornerRadius(8.dp.toPx()),
            style = Stroke(width = stroke),
        )
    }
}
