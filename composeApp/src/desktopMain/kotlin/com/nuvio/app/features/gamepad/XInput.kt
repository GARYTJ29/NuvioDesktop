package com.nuvio.app.features.gamepad

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure

/** The buttons of an XInput pad, named the way the bindings talk about them. */
internal enum class GamepadButton {
    A, B, X, Y, LB, RB, LT, RT, BACK, START, L3, R3, UP, DOWN, LEFT, RIGHT;

    val isDirection: Boolean
        get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
}

internal class GamepadSnapshot(
    val buttons: Int,
    val leftTrigger: Float,
    val rightTrigger: Float,
    val leftX: Float,
    val leftY: Float,
    val rightX: Float,
    val rightY: Float,
)

/**
 * XInputGetState through JNA, which the app already ships. XInput covers Xbox
 * pads and anything presenting as one (Steam Input, DS4Windows, most third-party
 * pads); it never reports headsets or other HID devices as controllers.
 */
internal object XInput {
    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LEFT_THUMB = 0x0040
    const val RIGHT_THUMB = 0x0080
    const val LEFT_SHOULDER = 0x0100
    const val RIGHT_SHOULDER = 0x0200
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000

    const val MAX_USERS = 4
    private const val ERROR_SUCCESS = 0

    @Structure.FieldOrder(
        "dwPacketNumber",
        "wButtons",
        "bLeftTrigger",
        "bRightTrigger",
        "sThumbLX",
        "sThumbLY",
        "sThumbRX",
        "sThumbRY",
    )
    class State : Structure() {
        @JvmField var dwPacketNumber: Int = 0
        @JvmField var wButtons: Short = 0
        @JvmField var bLeftTrigger: Byte = 0
        @JvmField var bRightTrigger: Byte = 0
        @JvmField var sThumbLX: Short = 0
        @JvmField var sThumbLY: Short = 0
        @JvmField var sThumbRX: Short = 0
        @JvmField var sThumbRY: Short = 0
    }

    @Suppress("FunctionName")
    private interface Api : Library {
        fun XInputGetState(userIndex: Int, state: State): Int
    }

    private val api: Api? by lazy {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return@lazy null
        listOf("xinput1_4", "xinput9_1_0", "xinput1_3").firstNotNullOfOrNull { name ->
            runCatching { Native.load(name, Api::class.java) }.getOrNull()
        }
    }

    val isAvailable: Boolean
        get() = api != null

    private val state = State()

    /** The pad in [userIndex], or null when that slot is empty. */
    fun read(userIndex: Int): GamepadSnapshot? {
        val lib = api ?: return null
        val result = runCatching { lib.XInputGetState(userIndex, state) }.getOrNull() ?: return null
        if (result != ERROR_SUCCESS) return null
        return GamepadSnapshot(
            buttons = state.wButtons.toInt() and 0xFFFF,
            leftTrigger = (state.bLeftTrigger.toInt() and 0xFF) / 255f,
            rightTrigger = (state.bRightTrigger.toInt() and 0xFF) / 255f,
            leftX = axis(state.sThumbLX),
            leftY = axis(state.sThumbLY),
            rightX = axis(state.sThumbRX),
            rightY = axis(state.sThumbRY),
        )
    }

    private fun axis(value: Short): Float =
        (value.toFloat() / if (value < 0) 32768f else 32767f).coerceIn(-1f, 1f)
}
