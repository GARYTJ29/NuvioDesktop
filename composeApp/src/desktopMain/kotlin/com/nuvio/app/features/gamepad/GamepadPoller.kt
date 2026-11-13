package com.nuvio.app.features.gamepad

import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import javax.swing.SwingUtilities
import kotlin.math.abs

/**
 * Polls the pad on its own thread and hands edges to [GamepadRouter] on the
 * Swing thread, which is Compose's UI thread on desktop. A port of the tick /
 * handleButton loop of the Stremio shell's gamepad.cpp: the same repeat timing,
 * stick hysteresis and pre-seeding of buttons already held on connect.
 */
internal object GamepadPoller {
    private val log = Logger.withTag("Gamepad")

    private const val POLL_INTERVAL_MS = 8L
    private const val IDLE_INTERVAL_MS = 100L
    private const val SCAN_INTERVAL_MS = 1_000L

    // Pixels per poll at full deflection. The shell scrolled 30px a frame at
    // 60fps; this polls about twice as often.
    private const val SCROLL_STEP = 15f

    private val REPEATABLE = setOf(
        GamepadButton.UP, GamepadButton.DOWN, GamepadButton.LEFT, GamepadButton.RIGHT,
        GamepadButton.LT, GamepadButton.RT,
    )

    // Only while the on-screen keyboard is up: backspace and the caret keys.
    private val OSK_REPEATABLE = setOf(GamepadButton.X, GamepadButton.LB, GamepadButton.RB)

    private class Held(var down: Boolean = false, var next: Long = 0L)

    private val held = GamepadButton.entries.associateWith { Held() }
    private val stickHeld = mutableMapOf(
        GamepadButton.UP to false, GamepadButton.DOWN to false,
        GamepadButton.LEFT to false, GamepadButton.RIGHT to false,
    )

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var restartRepeatsRequested = false

    private var userIndex = -1
    private var lastScanAt = 0L
    private var active = false

    fun start() {
        if (thread != null || !XInput.isAvailable) return
        thread = Thread(::run, "nuvio-gamepad").apply {
            isDaemon = true
            start()
        }
    }

    /** Gives held directions the full delay again, so a route change moves one step. */
    fun restartRepeats() {
        restartRepeatsRequested = true
    }

    private fun run() {
        log.i { "gamepad polling started" }
        while (true) {
            val sleep = runCatching { tick() }
                .onFailure { error -> log.w(error) { "gamepad tick failed" } }
                .getOrDefault(IDLE_INTERVAL_MS)
            Thread.sleep(sleep)
        }
    }

    private fun tick(): Long {
        val settings = DesktopControllerSettings.current
        if (!settings.enabled) {
            deactivate()
            return IDLE_INTERVAL_MS
        }
        val pad = currentPad() ?: run {
            deactivate()
            return IDLE_INTERVAL_MS
        }
        // Like the browser Gamepad API the shell used: no input reaches the app
        // while another app is in front.
        if (!AppForeground.isForeground()) {
            releaseAll()
            return IDLE_INTERVAL_MS
        }

        val now = System.currentTimeMillis()
        val state = readPad(pad, settings.deadzone)
        if (!active) {
            active = true
            dispatch { GamepadRouter.onConnectionChanged(true) }
            // Buttons already down when polling starts are not new presses.
            state.forEach { (button, down) ->
                if (down) held.getValue(button).apply { this.down = true; next = now + settings.repeatDelayMs }
            }
        }
        if (restartRepeatsRequested) {
            restartRepeatsRequested = false
            held.values.filter { it.down }.forEach { it.next = now + settings.repeatDelayMs }
        }

        val lbHeld = state.getValue(GamepadButton.LB)
        val rbHeld = state.getValue(GamepadButton.RB)
        for (button in GamepadButton.entries) {
            handleButton(button, state.getValue(button), now, lbHeld, rbHeld, settings)
        }

        val dz = settings.deadzone
        val dx = if (abs(pad.rightX) > dz) pad.rightX * SCROLL_STEP else 0f
        // XInput's Y axis points up; scrolling down is positive.
        val dy = if (abs(pad.rightY) > dz) -pad.rightY * SCROLL_STEP else 0f
        if (dx != 0f || dy != 0f) dispatch { GamepadRouter.onScroll(dx, dy) }
        return POLL_INTERVAL_MS
    }

    private fun handleButton(
        button: GamepadButton,
        down: Boolean,
        now: Long,
        lbHeld: Boolean,
        rbHeld: Boolean,
        settings: ControllerSettings,
    ) {
        val s = held.getValue(button)
        if (down) {
            if (!s.down) {
                s.down = true
                s.next = now + settings.repeatDelayMs
                dispatch { GamepadRouter.onPress(GamepadPress(button, repeat = false, lbHeld = lbHeld, rbHeld = rbHeld)) }
            } else if ((button in REPEATABLE || (GamepadRouter.isKeyboardOpen && button in OSK_REPEATABLE)) && now >= s.next) {
                s.next = now + settings.repeatRateMs
                dispatch { GamepadRouter.onPress(GamepadPress(button, repeat = true, lbHeld = lbHeld, rbHeld = rbHeld)) }
            }
        } else if (s.down) {
            s.down = false
            dispatch { GamepadRouter.onRelease(button) }
        }
    }

    private fun readPad(pad: GamepadSnapshot, deadzone: Float): Map<GamepadButton, Boolean> {
        val bits = pad.buttons
        fun bit(mask: Int) = bits and mask != 0
        readStick(pad.leftX, GamepadButton.LEFT, GamepadButton.RIGHT, deadzone)
        // XInput's Y axis points up, the opposite of the browser's.
        readStick(-pad.leftY, GamepadButton.UP, GamepadButton.DOWN, deadzone)
        return mapOf(
            GamepadButton.A to bit(XInput.A),
            GamepadButton.B to bit(XInput.B),
            GamepadButton.X to bit(XInput.X),
            GamepadButton.Y to bit(XInput.Y),
            GamepadButton.LB to bit(XInput.LEFT_SHOULDER),
            GamepadButton.RB to bit(XInput.RIGHT_SHOULDER),
            GamepadButton.LT to (pad.leftTrigger > 0.5f),
            GamepadButton.RT to (pad.rightTrigger > 0.5f),
            GamepadButton.BACK to bit(XInput.BACK),
            GamepadButton.START to bit(XInput.START),
            GamepadButton.L3 to bit(XInput.LEFT_THUMB),
            GamepadButton.R3 to bit(XInput.RIGHT_THUMB),
            GamepadButton.UP to (bit(XInput.DPAD_UP) || stickHeld.getValue(GamepadButton.UP)),
            GamepadButton.DOWN to (bit(XInput.DPAD_DOWN) || stickHeld.getValue(GamepadButton.DOWN)),
            GamepadButton.LEFT to (bit(XInput.DPAD_LEFT) || stickHeld.getValue(GamepadButton.LEFT)),
            GamepadButton.RIGHT to (bit(XInput.DPAD_RIGHT) || stickHeld.getValue(GamepadButton.RIGHT)),
        )
    }

    // A direction engages at the deadzone but only lets go well inside it, so a
    // stick resting near the threshold does not flicker into fresh presses.
    private fun readStick(value: Float, negative: GamepadButton, positive: GamepadButton, deadzone: Float) {
        val exit = deadzone * 0.6f
        stickHeld[negative] = value <= -(if (stickHeld.getValue(negative)) exit else deadzone)
        stickHeld[positive] = value >= (if (stickHeld.getValue(positive)) exit else deadzone)
    }

    // Polling an empty XInput slot is slow, so empty slots are only rescanned
    // once a second; a connected pad is read every tick.
    private fun currentPad(): GamepadSnapshot? {
        if (userIndex >= 0) {
            XInput.read(userIndex)?.let { return it }
            userIndex = -1
        }
        val now = System.currentTimeMillis()
        if (now - lastScanAt < SCAN_INTERVAL_MS) return null
        lastScanAt = now
        for (index in 0 until XInput.MAX_USERS) {
            val pad = XInput.read(index) ?: continue
            userIndex = index
            log.i { "controller found in XInput slot $index" }
            return pad
        }
        return null
    }

    private fun releaseAll() {
        held.forEach { (button, s) ->
            if (s.down) {
                s.down = false
                dispatch { GamepadRouter.onRelease(button) }
            }
        }
        stickHeld.keys.forEach { stickHeld[it] = false }
    }

    private fun deactivate() {
        releaseAll()
        if (active) {
            active = false
            dispatch { GamepadRouter.onConnectionChanged(false) }
        }
    }

    private fun dispatch(block: () -> Unit) {
        SwingUtilities.invokeLater {
            runCatching(block).onFailure { error -> log.w(error) { "gamepad dispatch failed" } }
        }
    }
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
