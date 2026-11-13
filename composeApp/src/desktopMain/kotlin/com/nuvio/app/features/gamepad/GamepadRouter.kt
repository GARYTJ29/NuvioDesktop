package com.nuvio.app.features.gamepad

import com.nuvio.app.core.input.AppNavigationBridge
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.player.desktop.DesktopPlayerPictureInPicture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.controller_connected
import org.jetbrains.compose.resources.getString
import java.util.concurrent.CopyOnWriteArrayList

internal interface GamepadInputHandler {
    /** Returns true when the press was used and should go no further. */
    fun onPress(press: GamepadPress): Boolean

    fun onRelease(button: GamepadButton): Boolean = false

    fun onScroll(dx: Float, dy: Float): Boolean = false
}

/**
 * Decides who gets each press, in the shell's order: an open power dialog or
 * prompt, then the on-screen keyboard, then the player, then the app's own
 * focus navigation. Runs on the Swing (Compose UI) thread.
 */
internal object GamepadRouter {
    private val overlays = CopyOnWriteArrayList<GamepadInputHandler>()
    private val activityListeners = CopyOnWriteArrayList<() -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val connectedState = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = connectedState.asStateFlow()

    /** Read from the poll thread to decide which buttons repeat. */
    @Volatile
    var isKeyboardOpen: Boolean = false

    var browseHandler: GamepadInputHandler? = null
    var playerHandler: GamepadInputHandler? = null

    /** LB+X, from anywhere. Set by the power menu. */
    var powerMenuOpener: (() -> Unit)? = null

    // LB outside the player cycles tabs, but it is also half of LB+X and LB+R3,
    // so the press only arms this and the release spends it.
    private var lbPending = false

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

    fun onConnectionChanged(isConnected: Boolean) {
        connectedState.value = isConnected
        if (isConnected) {
            scope.launch { NuvioToastController.show(getString(Res.string.controller_connected), 2200L) }
        } else {
            lbPending = false
        }
    }

    fun onPress(press: GamepadPress) {
        activityListeners.forEach { it() }
        AppNavigationBridge.reportDirectionalInput(true)
        for (overlay in overlays.reversed()) {
            if (overlay.onPress(press)) return
        }
        val player = activePlayer()
        when {
            press.button == GamepadButton.X && press.lbHeld -> {
                lbPending = false
                powerMenuOpener?.invoke()
                return
            }
            press.button == GamepadButton.R3 && press.lbHeld -> {
                lbPending = false
                if (player == null) AppNavigationBridge.requestReload()
                return
            }
            press.button == GamepadButton.LB && player == null -> {
                lbPending = true
                return
            }
        }
        if (player != null) {
            player.onPress(press)
        } else {
            browseHandler?.onPress(press)
        }
    }

    fun onRelease(button: GamepadButton) {
        for (overlay in overlays.reversed()) {
            if (overlay.onRelease(button)) return
        }
        val player = activePlayer()
        if (button == GamepadButton.LB && lbPending) {
            lbPending = false
            if (player == null) {
                AppNavigationBridge.cycleTab(-1)
                return
            }
        }
        if (player != null) player.onRelease(button) else browseHandler?.onRelease(button)
    }

    fun onScroll(dx: Float, dy: Float) {
        for (overlay in overlays.reversed()) {
            if (overlay.onScroll(dx, dy)) return
        }
        if (activePlayer() != null) return
        browseHandler?.onScroll(dx, dy)
    }
}
