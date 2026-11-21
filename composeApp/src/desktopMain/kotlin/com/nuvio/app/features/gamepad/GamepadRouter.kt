package com.nuvio.app.features.gamepad

import com.nuvio.app.controller.ControllerDirection
import com.nuvio.app.controller.ControllerEvent
import com.nuvio.app.controller.ControllerManager
import com.nuvio.app.controller.XboxButton
import com.nuvio.app.core.input.AppNavigationBridge
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.player.desktop.DesktopPlayerPictureInPicture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.controller_connected
import org.jetbrains.compose.resources.getString
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

internal interface GamepadInputHandler {
    /** Returns true when the press was used and should go no further. */
    fun onPress(press: GamepadPress): Boolean

    fun onRelease(button: GamepadButton): Boolean = false

    fun onScroll(dx: Float, dy: Float): Boolean = false
}

/**
 * Sits in front of the shared controller engine ([ControllerManager]) and takes
 * the input that belongs to the desktop's own pad features: an open power dialog
 * or the on-screen keyboard first, then the player, then the LB+X / LB+RS
 * shortcuts and LS for fullscreen. Everything else goes on to the spatial engine
 * untouched, which owns browsing, the focus ring and the hint toolbar.
 *
 * The engine's poller calls [intercept] on its own thread; input taken here is
 * handled on the Swing (Compose UI) thread, and anything no handler uses is
 * passed back to the engine from there.
 */
internal object GamepadRouter {
    private val overlays = CopyOnWriteArrayList<GamepadInputHandler>()
    private val activityListeners = CopyOnWriteArrayList<() -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Same timing as the engine's held directions.
    private const val REPEAT_DELAY_MS = 320L
    private const val REPEAT_INTERVAL_MS = 105L

    // LT/RT step through the player; X and LB/RB are backspace and the caret
    // keys while the on-screen keyboard is up.
    private val REPEATABLE = setOf(GamepadButton.LT, GamepadButton.RT)
    private val KEYBOARD_REPEATABLE = setOf(GamepadButton.X, GamepadButton.LB, GamepadButton.RB)

    val connected: StateFlow<Boolean> = ControllerManager.connectedControllerCount
        .map { it > 0 }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    @Volatile
    var isKeyboardOpen: Boolean = false

    /** True while a Compose text field holds focus (and so an input session). */
    @Volatile
    var isTextFieldFocused: Boolean = false

    /** A on a focused text field: opens the on-screen keyboard; true when it did. */
    var textFieldActivator: (() -> Boolean)? = null

    /** LS outside the player. */
    var fullscreenToggler: (() -> Unit)? = null

    /** Called on the Swing thread before each press; puts AWT focus back on Compose if it was lost. */
    var focusGuard: (() -> Unit)? = null

    @Volatile
    var playerHandler: GamepadInputHandler? = null
        set(value) {
            val closed = field != null && value == null
            field = value
            if (closed) playerClosedListener?.invoke()
        }

    /**
     * Called on the Swing thread once the player stops taking input. The
     * player's native views held keyboard focus, and Windows does not hand it
     * back to the app window when they are destroyed.
     */
    var playerClosedListener: (() -> Unit)? = null

    /** LB+X, from anywhere. Set by the power menu. */
    var powerMenuOpener: (() -> Unit)? = null

    // Read on the poller thread, written on it too.
    @Volatile private var lbHeld = false
    @Volatile private var rbHeld = false

    // Swing thread only. LB outside the player switches tabs, but it is also half
    // of LB+X and LB+RS, so its press is held back and its release spends it.
    private var lbPending = false
    private val repeatJobs = HashMap<GamepadButton, Job>()
    private var lastActivityAt = 0L

    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        ControllerManager.inputInterceptor = ::intercept
        scope.launch {
            connected.drop(1).collect { isConnected ->
                if (isConnected) {
                    NuvioToastController.show(getString(Res.string.controller_connected), 2200L)
                } else {
                    lbPending = false
                    stopRepeats()
                }
            }
        }
    }

    /** Adds a handler that sees input before everything else; returns its remover. */
    fun pushOverlay(handler: GamepadInputHandler): () -> Unit {
        overlays.add(handler)
        return { overlays.remove(handler) }
    }

    fun addActivityListener(listener: () -> Unit): () -> Unit {
        activityListeners.add(listener)
        return { activityListeners.remove(listener) }
    }

    private fun activePlayer(): GamepadInputHandler? =
        playerHandler?.takeUnless { DesktopPlayerPictureInPicture.isEnabled }

    // ------------------------------------------------------------ poller thread

    private fun intercept(event: ControllerEvent): Boolean {
        when (event) {
            is ControllerEvent.ButtonPressed -> when (event.button) {
                XboxButton.LB -> lbHeld = true
                XboxButton.RB -> rbHeld = true
                else -> Unit
            }
            is ControllerEvent.ButtonReleased -> when (event.button) {
                XboxButton.LB -> lbHeld = false
                XboxButton.RB -> rbHeld = false
                else -> Unit
            }
            else -> Unit
        }
        if (!DesktopControllerSettings.current.enabled) return true
        // Like the browser Gamepad API the shell used: no input reaches the app
        // while another app is in front.
        if (!AppForeground.isForeground()) return true

        val isPress = event is ControllerEvent.ButtonPressed || event is ControllerEvent.Navigate
        if (isPress || event is ControllerEvent.AnalogScroll) {
            SwingUtilities.invokeLater {
                noteActivity()
                if (isPress) focusGuard?.invoke()
            }
        }

        if (!isOurs(event)) {
            // The press may have been ours (the player or keyboard closed since): stop its repeat.
            if (event is ControllerEvent.ButtonReleased) {
                event.button.toGamepadButton()?.let { button ->
                    SwingUtilities.invokeLater { repeatJobs.remove(button)?.cancel() }
                }
            }
            return false
        }
        val lb = lbHeld
        val rb = rbHeld
        SwingUtilities.invokeLater { route(event, lb, rb) }
        return true
    }

    private fun isOurs(event: ControllerEvent): Boolean {
        if (overlays.isNotEmpty() || activePlayer() != null) return true
        return when (event) {
            is ControllerEvent.ButtonPressed -> when (event.button) {
                XboxButton.LB, XboxButton.LS -> true
                XboxButton.X, XboxButton.RS -> lbHeld
                XboxButton.A -> isTextFieldFocused && DesktopControllerSettings.current.virtualKeyboard
                else -> false
            }
            is ControllerEvent.ButtonReleased -> event.button == XboxButton.LB
            else -> false
        }
    }

    // ------------------------------------------------------------- Swing thread

    private fun noteActivity() {
        val now = System.currentTimeMillis()
        if (now - lastActivityAt < 250L) return
        lastActivityAt = now
        activityListeners.forEach { it() }
    }

    private fun route(event: ControllerEvent, lb: Boolean, rb: Boolean) {
        val used = when (event) {
            is ControllerEvent.Navigate ->
                onPress(GamepadPress(event.direction.toGamepadButton(), event.isRepeat, lb, rb))
            is ControllerEvent.ButtonPressed -> {
                val button = event.button.toGamepadButton() ?: return
                onPress(GamepadPress(button, repeat = false, lbHeld = lb, rbHeld = rb)).also { used ->
                    if (used) startRepeat(button)
                }
            }
            is ControllerEvent.ButtonReleased -> {
                val button = event.button.toGamepadButton() ?: return
                repeatJobs.remove(button)?.cancel()
                onRelease(button)
            }
            is ControllerEvent.AnalogScroll -> onScroll(event.dx, event.dy)
        }
        if (!used) ControllerManager.dispatchToEngine(event)
    }

    // Internal for tests, which press buttons without a pad.
    internal fun onPress(press: GamepadPress): Boolean {
        for (overlay in overlays.reversed()) {
            if (overlay.onPress(press)) return true
        }
        val player = activePlayer()
        when {
            press.button == GamepadButton.X && press.lbHeld -> {
                lbPending = false
                powerMenuOpener?.invoke()
                return true
            }
            press.button == GamepadButton.R3 && press.lbHeld -> {
                lbPending = false
                if (player == null) AppNavigationBridge.requestReload()
                return true
            }
            press.button == GamepadButton.LB && player == null -> {
                lbPending = true
                return true
            }
        }
        if (player != null) return player.onPress(press)
        return when (press.button) {
            GamepadButton.L3 -> {
                fullscreenToggler?.invoke()
                fullscreenToggler != null
            }
            GamepadButton.A -> textFieldActivator?.invoke() == true
            else -> false
        }
    }

    internal fun onRelease(button: GamepadButton): Boolean {
        for (overlay in overlays.reversed()) {
            if (overlay.onRelease(button)) return true
        }
        val player = activePlayer()
        if (button == GamepadButton.LB && lbPending) {
            lbPending = false
            if (player == null) {
                // A plain LB: the engine's tab switch, which it does on press.
                ControllerManager.dispatchToEngine(ControllerEvent.ButtonPressed(XboxButton.LB))
                ControllerManager.dispatchToEngine(ControllerEvent.ButtonReleased(XboxButton.LB))
                return true
            }
        }
        if (button == GamepadButton.LB && player == null) return true
        return player?.onRelease(button) ?: false
    }

    private fun onScroll(dx: Float, dy: Float): Boolean {
        for (overlay in overlays.reversed()) {
            if (overlay.onScroll(dx, dy)) return true
        }
        return activePlayer() != null
    }

    private fun startRepeat(button: GamepadButton) {
        val repeatable = button in REPEATABLE || (isKeyboardOpen && button in KEYBOARD_REPEATABLE)
        if (!repeatable) return
        repeatJobs.remove(button)?.cancel()
        repeatJobs[button] = scope.launch {
            delay(REPEAT_DELAY_MS)
            while (true) {
                onPress(GamepadPress(button, repeat = true, lbHeld = lbHeld, rbHeld = rbHeld))
                delay(REPEAT_INTERVAL_MS)
            }
        }
    }

    private fun stopRepeats() {
        repeatJobs.values.forEach { it.cancel() }
        repeatJobs.clear()
    }

    private fun ControllerDirection.toGamepadButton(): GamepadButton = when (this) {
        ControllerDirection.Up -> GamepadButton.UP
        ControllerDirection.Down -> GamepadButton.DOWN
        ControllerDirection.Left -> GamepadButton.LEFT
        ControllerDirection.Right -> GamepadButton.RIGHT
    }

    private fun XboxButton.toGamepadButton(): GamepadButton? = when (this) {
        XboxButton.A -> GamepadButton.A
        XboxButton.B -> GamepadButton.B
        XboxButton.X -> GamepadButton.X
        XboxButton.Y -> GamepadButton.Y
        XboxButton.LB -> GamepadButton.LB
        XboxButton.RB -> GamepadButton.RB
        XboxButton.LT -> GamepadButton.LT
        XboxButton.RT -> GamepadButton.RT
        XboxButton.LS -> GamepadButton.L3
        XboxButton.RS -> GamepadButton.R3
        XboxButton.View -> GamepadButton.BACK
        XboxButton.Menu -> GamepadButton.START
        XboxButton.DPadUp -> GamepadButton.UP
        XboxButton.DPadDown -> GamepadButton.DOWN
        XboxButton.DPadLeft -> GamepadButton.LEFT
        XboxButton.DPadRight -> GamepadButton.RIGHT
    }
}
