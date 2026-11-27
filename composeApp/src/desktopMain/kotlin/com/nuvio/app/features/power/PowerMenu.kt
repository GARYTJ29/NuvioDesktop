package com.nuvio.app.features.power

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.features.gamepad.GamepadButton
import com.nuvio.app.features.gamepad.GamepadInputHandler
import com.nuvio.app.features.gamepad.GamepadPress
import com.nuvio.app.features.gamepad.GamepadRouter

internal enum class PowerMode { Menu, Idle }

internal enum class PowerRow(val isButton: Boolean) {
    Action(false),
    When(false),
    CancelTimer(true),
    Cancel(true),
    Ok(true),
    StillHere(true),
    ActNow(true),
}

/**
 * The power dialog and the idle prompt, as a state machine the UI draws. A port
 * of the shell's power-menu.js: rows are browsed with up/down, A (or Enter)
 * opens a value for editing, and while editing the d-pad changes it; B backs
 * out of an edit before it closes the dialog. Runs on the UI thread.
 */
internal object PowerMenu {
    const val MAX_HOURS = 24
    const val MAX_MINUTES = 60

    // A run of more than five presses the same way, each within 450 ms, is
    // someone dialling a long way: the minutes then move in fives.
    private const val ACCEL_AFTER = 5
    private const val ACCEL_WINDOW_MS = 450L
    private const val ACCEL_STEP = 5
    private const val TYPE_WINDOW_MS = 1200L

    var mode by mutableStateOf<PowerMode?>(null)
        private set
    var rows by mutableStateOf(emptyList<PowerRow>())
        private set
    var selected by mutableIntStateOf(0)
        private set
    var editing by mutableStateOf(false)
        private set

    /** 0 = hours, 1 = minutes, on the When row. */
    var field by mutableIntStateOf(0)
        private set
    var choiceAction by mutableStateOf(PowerAction.Quit)
        private set
    var hours by mutableIntStateOf(0)
        private set
    var minutes by mutableIntStateOf(0)
        private set
    var idleDeadlineMillis by mutableLongStateOf(0L)
        private set
    var idleAction by mutableStateOf(PowerAction.Sleep)
        private set

    /** Called whenever the dialog closes: whoever closed it is evidently present. */
    var onClosed: (() -> Unit)? = null

    private var stepRun = 0
    private var stepDir = 0
    private var stepAt = 0L
    private var typeBuffer = ""
    private var typeAt = 0L
    private var removeOverlay: (() -> Unit)? = null

    val selectedRow: PowerRow? get() = rows.getOrNull(selected)

    /** Opens the menu, or closes it if it is already up (the shortcut toggles). */
    fun toggle() {
        if (mode == PowerMode.Menu) {
            close()
            return
        }
        if (mode == PowerMode.Idle) close()
        val settings = PowerSettingsStore.current
        choiceAction = settings.lastAction
        val saved = settings.lastDelayMinutes.coerceIn(0, MAX_HOURS * 60 - 1)
        hours = saved / 60
        minutes = saved % 60
        rows = buildList {
            add(PowerRow.Action)
            add(PowerRow.When)
            if (PowerTimer.armed.value != null) add(PowerRow.CancelTimer)
            add(PowerRow.Cancel)
            add(PowerRow.Ok)
        }
        // The choice is remembered, so landing on OK makes a repeat one press.
        selected = rows.lastIndex
        show(PowerMode.Menu)
    }

    fun openIdlePrompt(action: PowerAction, promptSeconds: Int) {
        if (mode != null) return
        idleAction = action
        idleDeadlineMillis = System.currentTimeMillis() + promptSeconds * 1000L
        rows = listOf(PowerRow.StillHere, PowerRow.ActNow)
        selected = 0
        show(PowerMode.Idle)
    }

    private fun show(next: PowerMode) {
        editing = false
        field = 0
        stepRun = 0
        typeBuffer = ""
        mode = next
        if (removeOverlay == null) removeOverlay = GamepadRouter.pushOverlay(gamepadHandler)
    }

    fun close() {
        if (mode == null) return
        mode = null
        editing = false
        field = 0
        rows = emptyList()
        removeOverlay?.invoke()
        removeOverlay = null
        onClosed?.invoke()
    }

    /** Ticks the idle countdown; runs the action when it reaches zero. */
    fun tickIdle(now: Long = System.currentTimeMillis()) {
        if (mode != PowerMode.Idle || now < idleDeadlineMillis) return
        val action = idleAction
        close()
        PowerActions.run(action)
    }

    // --- navigation --------------------------------------------------------

    fun move(delta: Int) {
        if (rows.isEmpty()) return
        stepRun = 0
        typeBuffer = ""
        selected = (selected + delta).mod(rows.size)
    }

    private fun selectField(next: Int) {
        if (selectedRow != PowerRow.When) return
        val at = next.coerceIn(0, 1)
        if (at == field) return
        stepRun = 0
        typeBuffer = ""
        field = at
    }

    private fun changeEditing(on: Boolean) {
        editing = on
        if (on) field = 0
        stepRun = 0
        typeBuffer = ""
    }

    /** Browsing, a direction moves the selection; editing, it changes the value. */
    fun onDirection(horizontal: Boolean, dir: Int) {
        if (mode == PowerMode.Idle) {
            move(dir)
            return
        }
        val row = selectedRow
        if (editing && row != null && !row.isButton) {
            when (row) {
                PowerRow.Action -> bump(dir)
                // Up is more, so the screen direction is inverted for the value.
                PowerRow.When -> if (horizontal) selectField(field + dir) else bump(-dir)
                else -> Unit
            }
            return
        }
        move(dir)
    }

    /** Wheel or stepper arrow on a value: change it without entering edit mode. */
    fun bumpRow(row: PowerRow, targetField: Int, delta: Int) {
        val index = rows.indexOf(row)
        if (index < 0) return
        selected = index
        if (row == PowerRow.When) field = targetField
        bump(delta)
    }

    private fun bump(delta: Int) {
        when (selectedRow) {
            PowerRow.Action -> {
                stepRun = 0
                val actions = PowerAction.entries
                choiceAction = actions[(actions.indexOf(choiceAction) + delta).mod(actions.size)]
            }
            PowerRow.When -> {
                if (field == 0) {
                    // Only 24 stops on the hours, so no run-up there.
                    stepRun = 0
                    hours = (hours + delta).mod(MAX_HOURS)
                } else {
                    val dir = if (delta < 0) -1 else 1
                    minutes = (minutes + dir * stepAmount(dir)).mod(MAX_MINUTES)
                }
            }
            else -> Unit
        }
    }

    private fun stepAmount(dir: Int): Int {
        val now = System.currentTimeMillis()
        stepRun = if (dir == stepDir && now - stepAt < ACCEL_WINDOW_MS) stepRun + 1 else 1
        stepDir = dir
        stepAt = now
        return if (stepRun > ACCEL_AFTER) ACCEL_STEP else 1
    }

    fun activate() {
        when (val row = selectedRow ?: return) {
            PowerRow.CancelTimer -> {
                PowerTimer.cancel()
                close()
            }
            PowerRow.Cancel, PowerRow.StillHere -> close()
            PowerRow.Ok -> commit()
            PowerRow.ActNow -> {
                val action = idleAction
                close()
                PowerActions.run(action)
            }
            else -> if (!row.isButton) changeEditing(!editing)
        }
    }

    /** B backs out of an edit first, and only then closes the dialog. */
    fun back() {
        if (editing) changeEditing(false) else close()
    }

    private fun commit() {
        val action = choiceAction
        val total = hours * 60 + minutes
        PowerSettingsStore.rememberChoice(action, total)
        close()
        PowerTimer.arm(action, total)
    }

    // --- typed digits ------------------------------------------------------

    /**
     * Digits accumulate, so "0" then "2" reads as 02; a pause starts over, and
     * a full field hands on to the next one (2 3 4 5 fills in 23h 45m).
     */
    fun typeDigit(digit: Int) {
        if (selectedRow != PowerRow.When) return
        if (!editing) changeEditing(true)
        val now = System.currentTimeMillis()
        if (now - typeAt > TYPE_WINDOW_MS) typeBuffer = ""
        typeAt = now
        typeBuffer = (typeBuffer + digit).takeLast(2)
        var value = typeBuffer.toInt()
        var digits = typeBuffer.length
        val max = (if (field == 0) MAX_HOURS else MAX_MINUTES) - 1
        // A second digit that overruns the field starts a new value: 2 then 5
        // on the hours means 5, not 25.
        if (value > max) {
            value %= 10
            typeBuffer = value.toString()
            digits = 1
        }
        if (field == 0) hours = value else minutes = value
        if (digits >= 2 || value * 10 > max) {
            typeBuffer = ""
            selectField(field + 1)
        }
    }

    /** Backspace zeroes the field being edited. */
    fun clearField() {
        if (selectedRow != PowerRow.When) return
        if (!editing) changeEditing(true)
        typeBuffer = ""
        if (field == 0) hours = 0 else minutes = 0
    }

    // --- gamepad -----------------------------------------------------------

    // Every button is taken while the dialog is up, so nothing reaches the
    // player or the app behind it.
    private val gamepadHandler = object : GamepadInputHandler {
        override fun onPress(press: GamepadPress): Boolean {
            if (mode == PowerMode.Idle) {
                // Any press answers the question; only the buttons are navigated.
                when (press.button) {
                    GamepadButton.UP, GamepadButton.LEFT -> move(-1)
                    GamepadButton.DOWN, GamepadButton.RIGHT -> move(1)
                    GamepadButton.A, GamepadButton.START -> activate()
                    else -> close()
                }
                return true
            }
            when (press.button) {
                GamepadButton.UP -> onDirection(horizontal = false, dir = -1)
                GamepadButton.DOWN -> onDirection(horizontal = false, dir = 1)
                GamepadButton.LEFT -> onDirection(horizontal = true, dir = -1)
                GamepadButton.RIGHT -> onDirection(horizontal = true, dir = 1)
                GamepadButton.A, GamepadButton.START -> activate()
                GamepadButton.B, GamepadButton.BACK -> back()
                // LB+X again closes it, the same as the keyboard shortcut.
                GamepadButton.X -> if (press.lbHeld) close()
                else -> Unit
            }
            return true
        }

        override fun onRelease(button: GamepadButton): Boolean = true

        override fun onScroll(dx: Float, dy: Float): Boolean = true
    }
}
