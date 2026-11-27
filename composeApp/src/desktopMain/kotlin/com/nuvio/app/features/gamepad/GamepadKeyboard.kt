package com.nuvio.app.features.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.controller_keyboard_clear
import nuvio.composeapp.generated.resources.controller_keyboard_done
import nuvio.composeapp.generated.resources.controller_keyboard_hint
import nuvio.composeapp.generated.resources.controller_keyboard_shift
import nuvio.composeapp.generated.resources.controller_keyboard_space
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

// The shell's layout: each row as it reads unshifted, and shifted.
private val KeyboardRows = listOf(
    "`1234567890-=" to "~!@#$%^&*()_+",
    "qwertyuiop[]\\" to "QWERTYUIOP{}|",
    "asdfghjkl;'" to "ASDFGHJKL:\"",
    "zxcvbnm,./" to "ZXCVBNM<>?",
)

private enum class KeyAction(val grow: Float) { Shift(2f), Space(6f), Backspace(2f), Clear(2f), Done(2f) }

private const val BackspaceGlyph = "⌫"

/**
 * The shell's on-screen keyboard, typing into the focused Compose text field
 * with synthetic key events. The field keeps focus the whole time; nothing
 * here is focusable.
 */
@Composable
internal fun GamepadKeyboard(
    fieldBounds: Rect?,
    hostOrigin: Offset,
    onClose: () -> Unit,
) {
    var row by remember { mutableIntStateOf(0) }
    var column by remember { mutableIntStateOf(0) }
    var shift by remember { mutableStateOf(false) }
    val latestOnClose = rememberUpdatedState(onClose)

    fun rowLength(index: Int): Int =
        if (index < KeyboardRows.size) KeyboardRows[index].first.length else KeyAction.entries.size

    fun pressKey(rowIndex: Int, columnIndex: Int) {
        if (rowIndex < KeyboardRows.size) {
            val (lower, upper) = KeyboardRows[rowIndex]
            AwtKeys.type(if (shift) upper[columnIndex] else lower[columnIndex])
            // Shift covers the next character only, the way a real one does.
            shift = false
            return
        }
        when (KeyAction.entries[columnIndex]) {
            KeyAction.Shift -> shift = !shift
            KeyAction.Space -> AwtKeys.type(' ')
            KeyAction.Backspace -> AwtKeys.backspace()
            KeyAction.Clear -> AwtKeys.clearField()
            KeyAction.Done -> {
                latestOnClose.value()
                AwtKeys.enter()
            }
        }
    }

    fun move(button: GamepadButton) {
        val rows = KeyboardRows.size + 1
        when (button) {
            GamepadButton.LEFT -> column = (column - 1).mod(rowLength(row))
            GamepadButton.RIGHT -> column = (column + 1).mod(rowLength(row))
            GamepadButton.UP, GamepadButton.DOWN -> {
                val next = (row + if (button == GamepadButton.UP) -1 else 1).mod(rows)
                // Keep roughly the same place across rows of different lengths.
                val from = (column + 0.5f) / rowLength(row)
                column = (from * rowLength(next) - 0.5f).roundToInt().coerceIn(0, rowLength(next) - 1)
                row = next
            }
            else -> Unit
        }
    }

    DisposableEffect(Unit) {
        val remove = GamepadRouter.pushOverlay(object : GamepadInputHandler {
            override fun onPress(press: GamepadPress): Boolean {
                when (press.button) {
                    GamepadButton.UP, GamepadButton.DOWN,
                    GamepadButton.LEFT, GamepadButton.RIGHT -> move(press.button)
                    GamepadButton.A -> pressKey(row, column)
                    GamepadButton.B -> latestOnClose.value()
                    GamepadButton.X -> AwtKeys.backspace()
                    GamepadButton.Y -> shift = !shift
                    GamepadButton.LB -> AwtKeys.caretLeft()
                    GamepadButton.RB -> AwtKeys.caretRight()
                    GamepadButton.START -> {
                        latestOnClose.value()
                        AwtKeys.enter()
                    }
                    GamepadButton.BACK -> AwtKeys.clearField()
                    GamepadButton.LT, GamepadButton.RT -> Unit
                    // L3/R3 keep their usual meaning.
                    GamepadButton.L3, GamepadButton.R3 -> return false
                }
                return true
            }

            override fun onRelease(button: GamepadButton): Boolean = true

            override fun onScroll(dx: Float, dy: Float): Boolean = true
        })
        onDispose(remove)
    }

    val labels = mapOf(
        KeyAction.Shift to stringResource(Res.string.controller_keyboard_shift),
        KeyAction.Space to stringResource(Res.string.controller_keyboard_space),
        KeyAction.Backspace to BackspaceGlyph,
        KeyAction.Clear to stringResource(Res.string.controller_keyboard_clear),
        KeyAction.Done to stringResource(Res.string.controller_keyboard_done),
    )
    val hint = stringResource(Res.string.controller_keyboard_hint)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Docks in whichever half of the window the field is not in.
        val fieldCenterY = fieldBounds?.center?.y?.minus(hostOrigin.y)
        val fieldInBottomHalf = fieldCenterY != null && with(density) { fieldCenterY.toDp() } > maxHeight / 2
        Column(
            modifier = Modifier
                .align(if (fieldInBottomHalf) Alignment.TopCenter else Alignment.BottomCenter)
                .padding(24.dp)
                .widthIn(max = 960.dp)
                .fillMaxWidth()
                .background(Color(0xF2141414), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyboardRows.forEachIndexed { rowIndex, (lower, upper) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    val keys = if (shift) upper else lower
                    keys.forEachIndexed { columnIndex, char ->
                        KeyCap(
                            label = char.toString(),
                            selected = row == rowIndex && column == columnIndex,
                            highlighted = false,
                            modifier = Modifier.weight(1f),
                            onTap = {
                                row = rowIndex
                                column = columnIndex
                                pressKey(rowIndex, columnIndex)
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                KeyAction.entries.forEachIndexed { columnIndex, action ->
                    KeyCap(
                        label = labels.getValue(action),
                        selected = row == KeyboardRows.size && column == columnIndex,
                        highlighted = action == KeyAction.Shift && shift,
                        modifier = Modifier.weight(action.grow),
                        onTap = {
                            row = KeyboardRows.size
                            column = columnIndex
                            pressKey(KeyboardRows.size, columnIndex)
                        },
                    )
                }
            }
            Text(
                text = hint,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun KeyCap(
    label: String,
    selected: Boolean,
    highlighted: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val latestOnTap = rememberUpdatedState(onTap)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(44.dp)
            .background(
                when {
                    selected -> Color.White
                    highlighted -> Color(0x66FFFFFF)
                    else -> Color(0x1FFFFFFF)
                },
                shape,
            )
            // A tap, not a clickable: the key must not take focus off the field.
            .pointerInput(Unit) { detectTapGestures { latestOnTap.value() } },
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 18.sp,
        )
    }
}
