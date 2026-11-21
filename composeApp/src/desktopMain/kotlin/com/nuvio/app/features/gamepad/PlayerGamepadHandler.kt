package com.nuvio.app.features.gamepad

import com.nuvio.app.features.player.desktop.NativePlayerController

/**
 * Hands presses to the player overlay's gamepad.js, which decides what they
 * mean there. Only names and booleans cross over, so nothing needs escaping.
 */
internal class PlayerGamepadHandler(
    private val controller: NativePlayerController,
) : GamepadInputHandler {
    override fun onPress(press: GamepadPress): Boolean {
        controller.runControlsScript(
            "window.nuvioGamepad&&window.nuvioGamepad.press('${press.button.name}'," +
                "{lb:${press.lbHeld},rb:${press.rbHeld},repeat:${press.repeat}})",
        )
        return true
    }

    override fun onRelease(button: GamepadButton): Boolean {
        controller.runControlsScript("window.nuvioGamepad&&window.nuvioGamepad.release('${button.name}')")
        return true
    }

    override fun onScroll(dx: Float, dy: Float): Boolean = true

    fun setActive(active: Boolean) {
        controller.runControlsScript("window.nuvioGamepad&&window.nuvioGamepad.setActive($active)")
    }
}
