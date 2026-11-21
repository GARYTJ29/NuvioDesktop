package com.nuvio.app.features.gamepad

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * The buttons the desktop's own pad features (player overlay, power menu,
 * on-screen keyboard) speak in. The names cross into the player's gamepad.js.
 */
internal enum class GamepadButton {
    A, B, X, Y, LB, RB, LT, RT, BACK, START, L3, R3, UP, DOWN, LEFT, RIGHT;

    val isDirection: Boolean
        get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
}

internal data class GamepadPress(
    val button: GamepadButton,
    val repeat: Boolean,
    val lbHeld: Boolean,
    val rbHeld: Boolean,
)

/** Whether one of this process's windows is the one in front. */
internal object AppForeground {
    @Suppress("FunctionName")
    private interface User32 : Library {
        fun GetForegroundWindow(): Pointer?
        fun GetWindowThreadProcessId(hwnd: Pointer, processId: IntByReference): Int
    }

    private val user32: User32? by lazy {
        runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }

    private val pid = ProcessHandle.current().pid().toInt()

    fun isForeground(): Boolean {
        val lib = user32 ?: return true
        val hwnd = lib.GetForegroundWindow() ?: return false
        val processId = IntByReference()
        lib.GetWindowThreadProcessId(hwnd, processId)
        return processId.value == pid
    }
}
