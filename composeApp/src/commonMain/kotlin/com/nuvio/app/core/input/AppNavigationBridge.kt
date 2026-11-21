package com.nuvio.app.core.input

import com.nuvio.app.controller.ControllerManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lets platform input that lives outside the Compose tree (the desktop gamepad
 * bridge) ask the app to reload, and tells hover-driven UI when a controller is
 * driving, without reaching into MainAppContent's state.
 */
object AppNavigationBridge {
    private val reloadRequestFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reloadRequests: SharedFlow<Unit> = reloadRequestFlow.asSharedFlow()

    /**
     * True while a controller or the arrow keys drive the UI, until the mouse
     * really moves. Hover-driven chrome (the desktop sidebar, poster hover)
     * ignores a parked pointer meanwhile and follows focus instead.
     */
    val isDirectionalInputActive: StateFlow<Boolean> get() = ControllerManager.isNavigationActive

    fun requestReload() {
        reloadRequestFlow.tryEmit(Unit)
    }
}
