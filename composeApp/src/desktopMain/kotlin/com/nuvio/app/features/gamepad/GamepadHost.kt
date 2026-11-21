package com.nuvio.app.features.gamepad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.focus.getFocusedRect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import co.touchlab.kermit.Logger
import com.nuvio.app.controller.PlatformVirtualKeyboard
import com.nuvio.app.features.player.desktop.toggleDesktopAppFullscreen
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Window
import javax.swing.SwingUtilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// After the player closes, the screen it returns to may take a few frames to
// compose; keep offering focus this often, for up to two seconds.
private const val FocusSettleStepMs = 50L
private const val FocusSettleAttempts = 40

private val log = Logger.withTag("GamepadHost")

/**
 * Focus facts read outside composition. Updated straight from onFocusChanged:
 * a rememberUpdatedState copy would only catch up on the next recomposition,
 * too late for a retry loop or a press handled in the same frame.
 */
private class FocusTracker {
    var hostHasFocus = false

    /** The AWT component that held focus the last time Compose had it. */
    var composeComponent: Component? = null
}

/**
 * The desktop's own pad features around the app: the on-screen keyboard for text
 * fields, LS for fullscreen, and getting AWT focus back to Compose after the
 * player's native views had it. Browsing (focus ring, d-pad, scrolling, hints)
 * belongs to the shared controller engine; [GamepadRouter] decides which input
 * comes here.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GamepadHost(window: Window, content: @Composable () -> Unit) {
    val focusTracker = remember { FocusTracker() }
    // The focused text field's bounds, so the keyboard can sit clear of it.
    val focusProbe = remember { FocusRectProbe() }
    var focusedBounds by remember { mutableStateOf<Rect?>(null) }
    var textInputSessions by remember { mutableIntStateOf(0) }
    var keyboardVisible by remember { mutableStateOf(false) }

    // A text field holds an input session for as long as it is focused, which
    // is how a press of A knows it is on one.
    val textInputInterceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            textInputSessions++
            GamepadRouter.isTextFieldFocused = true
            try {
                nextHandler.startInputMethod(request)
            } finally {
                textInputSessions--
                GamepadRouter.isTextFieldFocused = textInputSessions > 0
            }
        }
    }

    LaunchedEffect(textInputSessions) {
        if (textInputSessions == 0) keyboardVisible = false
    }
    LaunchedEffect(keyboardVisible) {
        GamepadRouter.isKeyboardOpen = keyboardVisible
        if (!keyboardVisible) {
            focusedBounds = null
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            val rect = focusProbe.focusedRect()
            if (rect != focusedBounds) focusedBounds = rect
        }
    }

    // Compose's canvas: whichever AWT component holds focus while the host has
    // Compose focus.
    fun composeComponent(): Component? =
        focusTracker.composeComponent?.takeIf { it.isShowing && SwingUtilities.getWindowAncestor(it) === window }
            ?: window.focusTraversalPolicy?.getDefaultComponent(window)?.takeIf { it !== window && it.isShowing }

    // Whether key presses would miss Compose: the app is in front and focus
    // sits in this window but on nothing, or on some other component - after
    // the player, on what is left of its native views. A dialog window or the
    // PiP window holding focus is not lost focus, and neither is a background
    // app.
    fun focusIsLost(): Boolean {
        if (!window.isShowing || !AppForeground.isForeground()) return false
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return true
        val ownerWindow = owner as? Window ?: SwingUtilities.getWindowAncestor(owner)
        if (ownerWindow !== window) return false
        val compose = composeComponent() ?: return false
        return owner !== compose
    }

    val scope = rememberCoroutineScope()
    var recovery by remember { mutableStateOf<Job?>(null) }

    // Puts AWT focus back on Compose's canvas, retrying while the screen the
    // player returns to is still composing.
    fun recoverFocus(reason: String) {
        recovery?.cancel()
        recovery = scope.launch {
            repeat(FocusSettleAttempts) { attempt ->
                if (!AppForeground.isForeground()) return@launch
                val compose = composeComponent()
                val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                if (compose != null && owner === compose) {
                    if (attempt > 0) log.d { "focus recovered ($reason) after $attempt attempts" }
                    return@launch
                }
                if (attempt == 0) {
                    log.d {
                        "recovering focus ($reason): owner=${owner?.javaClass?.name} " +
                            "compose=${compose?.javaClass?.name}"
                    }
                }
                compose?.requestFocus()
                delay(FocusSettleStepMs)
            }
            log.w { "focus recovery ($reason) gave up" }
        }
    }

    DisposableEffect(window) {
        val playerClosed = {
            // This fires while the player is being disposed; let its video
            // panel leave the window before choosing a focus target.
            SwingUtilities.invokeLater { recoverFocus("player closed") }
        }
        val focusGuard = {
            if (recovery?.isActive != true && focusIsLost()) recoverFocus("controller input")
        }
        val openKeyboard = {
            if (textInputSessions > 0 && DesktopControllerSettings.current.virtualKeyboard) {
                keyboardVisible = true
                true
            } else {
                false
            }
        }
        val toggleFullscreen = { toggleDesktopAppFullscreen(window) }
        // The engine asks for the keyboard when A or Y lands on one of its text inputs.
        val keyboardDelegate = object : PlatformVirtualKeyboard.Delegate {
            override fun show(fromAutoFocus: Boolean) {
                if (fromAutoFocus || !DesktopControllerSettings.current.virtualKeyboard) return
                SwingUtilities.invokeLater { keyboardVisible = true }
            }

            override fun hide() {
                SwingUtilities.invokeLater { keyboardVisible = false }
            }

            override val isKeyboardActive: Boolean get() = GamepadRouter.isKeyboardOpen
        }

        GamepadRouter.playerClosedListener = playerClosed
        GamepadRouter.focusGuard = focusGuard
        GamepadRouter.textFieldActivator = openKeyboard
        GamepadRouter.fullscreenToggler = toggleFullscreen
        PlatformVirtualKeyboard.delegate = keyboardDelegate
        GamepadRouter.install()
        onDispose {
            if (GamepadRouter.playerClosedListener === playerClosed) GamepadRouter.playerClosedListener = null
            if (GamepadRouter.focusGuard === focusGuard) GamepadRouter.focusGuard = null
            if (GamepadRouter.textFieldActivator === openKeyboard) GamepadRouter.textFieldActivator = null
            if (GamepadRouter.fullscreenToggler === toggleFullscreen) GamepadRouter.fullscreenToggler = null
            if (PlatformVirtualKeyboard.delegate === keyboardDelegate) PlatformVirtualKeyboard.delegate = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged {
                focusTracker.hostHasFocus = it.hasFocus
                if (it.hasFocus) {
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                        ?.takeIf { owner -> SwingUtilities.getWindowAncestor(owner) === window }
                        ?.let { owner -> focusTracker.composeComponent = owner }
                }
            }
            .then(FocusRectProbeElement(focusProbe)),
    ) {
        InterceptPlatformTextInput(textInputInterceptor) {
            content()
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
