package com.nuvio.app.features.power

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.gamepad.GamepadRouter
import com.nuvio.app.features.player.desktop.ActiveDesktopPlayer
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.power_action_now
import nuvio.composeapp.generated.resources.power_action_quit
import nuvio.composeapp.generated.resources.power_action_shutdown
import nuvio.composeapp.generated.resources.power_action_sleep
import nuvio.composeapp.generated.resources.power_at
import nuvio.composeapp.generated.resources.power_cancel
import nuvio.composeapp.generated.resources.power_cancel_timer
import nuvio.composeapp.generated.resources.power_hint_button
import nuvio.composeapp.generated.resources.power_hint_edit_action
import nuvio.composeapp.generated.resources.power_hint_edit_time
import nuvio.composeapp.generated.resources.power_hint_value
import nuvio.composeapp.generated.resources.power_hint_value_typed
import nuvio.composeapp.generated.resources.power_idle_title
import nuvio.composeapp.generated.resources.power_immediately
import nuvio.composeapp.generated.resources.power_in
import nuvio.composeapp.generated.resources.power_menu_title
import nuvio.composeapp.generated.resources.power_ok
import nuvio.composeapp.generated.resources.power_row_action
import nuvio.composeapp.generated.resources.power_row_when
import nuvio.composeapp.generated.resources.power_still_here
import nuvio.composeapp.generated.resources.power_toast_armed
import nuvio.composeapp.generated.resources.power_toast_cancelled
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val Accent = Color(0xFFFFFFFF)
private val Panel = Color(0xF7161616)
private val RowIdle = Color(0x14FFFFFF)
private val RowSelected = Color(0x33FFFFFF)

internal val PowerAction.label: StringResource
    get() = when (this) {
        PowerAction.Quit -> Res.string.power_action_quit
        PowerAction.Sleep -> Res.string.power_action_sleep
        PowerAction.Shutdown -> Res.string.power_action_shutdown
    }

internal fun formatClock(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** "1h 30m", "45m" or "30s". */
internal fun formatRemaining(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) + 999) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}

/**
 * The power menu (Ctrl+Q / LB+X), the sleep timer's toasts and the idle check.
 * The dialogs are their own small windows: the player is a native surface that
 * would cover anything drawn inside the app window.
 */
@Composable
internal fun PowerHost(window: Window, onQuit: () -> Unit) {
    SideEffect { PowerActions.quitHandler = onQuit }
    DisposableEffect(window) {
        GamepadRouter.powerMenuOpener = { PowerMenu.toggle() }
        val shortcut = KeyEventDispatcher { event ->
            val isShortcut = event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_Q &&
                event.isControlDown && !event.isAltDown && !event.isShiftDown && !event.isMetaDown
            if (isShortcut) PowerMenu.toggle()
            isShortcut
        }
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focusManager.addKeyEventDispatcher(shortcut)
        val stopWatchdog = IdleWatchdog.start()
        onDispose {
            GamepadRouter.powerMenuOpener = null
            focusManager.removeKeyEventDispatcher(shortcut)
            stopWatchdog()
            PowerMenu.close()
        }
    }

    // The shell's toasts, also shown inside the player, which covers the app's.
    LaunchedEffect(Unit) {
        PowerTimer.events.collect { event ->
            val text = when (event) {
                is PowerTimerEvent.Armed -> getString(
                    Res.string.power_toast_armed,
                    getString(event.timer.action.label),
                    formatRemaining(event.timer.firesAtMillis - System.currentTimeMillis()),
                    formatClock(event.timer.firesAtMillis),
                )
                PowerTimerEvent.Cancelled -> getString(Res.string.power_toast_cancelled)
            }
            NuvioToastController.show(text, 2600L)
            ActiveDesktopPlayer.controller?.showOverlayToast(text)
        }
    }

    val mode = PowerMenu.mode ?: return
    PowerDialog(window, mode)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PowerDialog(owner: Window, mode: PowerMode) {
    val width = 440
    val height = if (mode == PowerMode.Menu) 400 else 220
    val state = rememberDialogState(
        position = WindowPosition(
            x = (owner.x + (owner.width - width) / 2).dp,
            y = (owner.y + (owner.height - height) / 2).dp,
        ),
        width = width.dp,
        height = height.dp,
    )
    DialogWindow(
        onCloseRequest = { PowerMenu.close() },
        state = state,
        title = stringResource(if (mode == PowerMode.Menu) Res.string.power_menu_title else Res.string.power_idle_title),
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) return@DialogWindow false
            if (event.isCtrlPressed || event.isAltPressed) return@DialogWindow false
            handleKey(event.key, mode)
        },
    ) {
        PowerDialogContent(mode)
    }
}

/** The keyboard, as in power-menu.js. Returns whether the key was used. */
private fun handleKey(key: Key, mode: PowerMode): Boolean {
    val digit = when (key) {
        Key.Zero, Key.NumPad0 -> 0
        Key.One, Key.NumPad1 -> 1
        Key.Two, Key.NumPad2 -> 2
        Key.Three, Key.NumPad3 -> 3
        Key.Four, Key.NumPad4 -> 4
        Key.Five, Key.NumPad5 -> 5
        Key.Six, Key.NumPad6 -> 6
        Key.Seven, Key.NumPad7 -> 7
        Key.Eight, Key.NumPad8 -> 8
        Key.Nine, Key.NumPad9 -> 9
        else -> null
    }
    if (mode == PowerMode.Idle) {
        when (key) {
            Key.DirectionUp, Key.DirectionLeft -> PowerMenu.move(-1)
            Key.DirectionDown, Key.DirectionRight, Key.Tab -> PowerMenu.move(1)
            Key.Enter, Key.NumPadEnter, Key.Spacebar -> PowerMenu.activate()
            // Any other key answers the question.
            else -> PowerMenu.close()
        }
        return true
    }
    when {
        digit != null -> PowerMenu.typeDigit(digit)
        key == Key.Backspace || key == Key.Delete -> PowerMenu.clearField()
        key == Key.DirectionUp -> PowerMenu.onDirection(horizontal = false, dir = -1)
        key == Key.DirectionDown -> PowerMenu.onDirection(horizontal = false, dir = 1)
        key == Key.DirectionLeft -> PowerMenu.onDirection(horizontal = true, dir = -1)
        key == Key.DirectionRight -> PowerMenu.onDirection(horizontal = true, dir = 1)
        key == Key.Tab -> PowerMenu.move(1)
        key == Key.Enter || key == Key.NumPadEnter || key == Key.Spacebar -> PowerMenu.activate()
        key == Key.Escape -> PowerMenu.back()
        else -> return false
    }
    return true
}

@Composable
private fun PowerDialogContent(mode: PowerMode) {
    // Refreshes the summary, chip and countdown once a second.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val armed by PowerTimer.armed.collectAsState()
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Panel, shape)
            .border(1.dp, Color(0x33FFFFFF), shape)
            .padding(22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(if (mode == PowerMode.Menu) Res.string.power_menu_title else Res.string.power_idle_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                val timer = armed
                if (mode == PowerMode.Menu && timer != null) {
                    Text(
                        text = stringResource(
                            Res.string.power_in,
                            stringResource(timer.action.label),
                            formatRemaining(timer.firesAtMillis - now),
                        ),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(RowSelected, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            if (mode == PowerMode.Menu) MenuRows(now) else IdleRows(now)
        }
    }
}

@Composable
private fun MenuRows(now: Long) {
    val rows = PowerMenu.rows
    val selectedRow = PowerMenu.selectedRow
    rows.filter { !it.isButton }.forEach { row ->
        val selected = row == selectedRow
        val editing = selected && PowerMenu.editing
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) RowSelected else RowIdle, RoundedCornerShape(10.dp))
                .border(if (editing) 2.dp else 0.dp, if (editing) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable { PowerMenu.bumpRow(row, PowerMenu.field, 1) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(if (row == PowerRow.Action) Res.string.power_row_action else Res.string.power_row_when),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            if (row == PowerRow.Action) {
                Text("‹  ${stringResource(PowerMenu.choiceAction.label)}  ›", color = Color.White, fontSize = 15.sp)
            } else {
                TimeField(PowerMenu.hours, "h", fieldIndex = 0, active = editing && PowerMenu.field == 0)
                Text("  ", color = Color.White)
                TimeField(PowerMenu.minutes, "m", fieldIndex = 1, active = editing && PowerMenu.field == 1)
            }
        }
    }

    val total = PowerMenu.hours * 60 + PowerMenu.minutes
    val actionLabel = stringResource(PowerMenu.choiceAction.label)
    Text(
        text = if (total > 0) {
            stringResource(Res.string.power_at, actionLabel, formatClock(now + total * 60_000L))
        } else {
            stringResource(Res.string.power_immediately, actionLabel)
        },
        color = Color.White.copy(alpha = 0.75f),
        fontSize = 14.sp,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        rows.filter { it.isButton }.forEach { row ->
            DialogButton(
                label = stringResource(
                    when (row) {
                        PowerRow.CancelTimer -> Res.string.power_cancel_timer
                        PowerRow.Cancel -> Res.string.power_cancel
                        else -> Res.string.power_ok
                    },
                ),
                selected = row == selectedRow,
                primary = row == PowerRow.Ok,
                modifier = Modifier.weight(1f),
                onClick = { selectAndActivate(row) },
            )
        }
    }

    // What the buttons do from here: an explicit edit mode is only usable on a
    // pad if it says so.
    val hint = when {
        selectedRow == null -> null
        PowerMenu.editing && selectedRow == PowerRow.Action -> Res.string.power_hint_edit_action
        PowerMenu.editing -> Res.string.power_hint_edit_time
        selectedRow.isButton -> Res.string.power_hint_button
        selectedRow == PowerRow.When -> Res.string.power_hint_value_typed
        else -> Res.string.power_hint_value
    }
    if (hint != null) {
        Text(
            text = stringResource(hint),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TimeField(value: Int, unit: String, fieldIndex: Int, active: Boolean) {
    Text(
        text = "${value.toString().padStart(2, '0')}$unit",
        color = if (active) Color.Black else Color.White,
        fontSize = 15.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .width(48.dp)
            .background(if (active) Accent else Color.Transparent, RoundedCornerShape(6.dp))
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (delta != 0f) PowerMenu.bumpRow(PowerRow.When, fieldIndex, if (delta < 0) 1 else -1)
            }
            .padding(vertical = 2.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun IdleRows(now: Long) {
    val secondsLeft = ((PowerMenu.idleDeadlineMillis - now + 999) / 1000).coerceAtLeast(0)
    val actionLabel = stringResource(PowerMenu.idleAction.label)
    Text(
        text = stringResource(Res.string.power_in, actionLabel, "${secondsLeft}s"),
        color = Color.White.copy(alpha = 0.8f),
        fontSize = 15.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        PowerMenu.rows.forEach { row ->
            DialogButton(
                label = if (row == PowerRow.StillHere) {
                    stringResource(Res.string.power_still_here)
                } else {
                    stringResource(Res.string.power_action_now, actionLabel)
                },
                selected = row == PowerMenu.selectedRow,
                primary = row == PowerRow.StillHere,
                modifier = Modifier.weight(1f),
                onClick = { selectAndActivate(row) },
            )
        }
    }
}

private fun selectAndActivate(row: PowerRow) {
    while (PowerMenu.selectedRow != row && PowerMenu.rows.contains(row)) PowerMenu.move(1)
    PowerMenu.activate()
}

@Composable
private fun DialogButton(
    label: String,
    selected: Boolean,
    primary: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .background(
                when {
                    selected -> Accent
                    primary -> RowSelected
                    else -> RowIdle
                },
                shape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 15.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
