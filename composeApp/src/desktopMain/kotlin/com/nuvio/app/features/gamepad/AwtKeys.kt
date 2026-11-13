package com.nuvio.app.features.gamepad

import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.KeyEvent

/**
 * A key event posted for the controller rather than typed on a keyboard, so
 * keyboard-only shortcuts (Escape leaving fullscreen) can tell the two apart.
 */
internal class SyntheticKeyEvent(
    source: Component,
    id: Int,
    `when`: Long,
    modifiers: Int,
    keyCode: Int,
    keyChar: Char,
) : KeyEvent(source, id, `when`, modifiers, keyCode, keyChar)

/**
 * Synthetic key presses posted to whatever holds AWT focus, which is Compose's
 * canvas. Going through the event queue means they reach the focused layer
 * (a dialog included) exactly the way a real keyboard's would.
 */
internal object AwtKeys {
    private fun target(): Component? =
        KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner

    fun tap(keyCode: Int, keyChar: Char = KeyEvent.CHAR_UNDEFINED, modifiers: Int = 0) {
        val component = target() ?: return
        val queue = Toolkit.getDefaultToolkit().systemEventQueue
        val now = System.currentTimeMillis()
        queue.postEvent(SyntheticKeyEvent(component, KeyEvent.KEY_PRESSED, now, modifiers, keyCode, keyChar))
        queue.postEvent(SyntheticKeyEvent(component, KeyEvent.KEY_RELEASED, now, modifiers, keyCode, keyChar))
    }

    /** One typed character, the event a text field inserts. */
    fun type(char: Char) {
        val component = target() ?: return
        Toolkit.getDefaultToolkit().systemEventQueue.postEvent(
            SyntheticKeyEvent(component, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, char),
        )
    }

    fun enter() = tap(KeyEvent.VK_ENTER, '\n')

    fun escape() = tap(KeyEvent.VK_ESCAPE, 27.toChar())

    fun tab(backwards: Boolean) =
        tap(KeyEvent.VK_TAB, '\t', if (backwards) KeyEvent.SHIFT_DOWN_MASK else 0)

    fun backspace() = tap(KeyEvent.VK_BACK_SPACE, '\b')

    fun caretLeft() = tap(KeyEvent.VK_LEFT)

    fun caretRight() = tap(KeyEvent.VK_RIGHT)

    /** Select all, then delete. */
    fun clearField() {
        tap(KeyEvent.VK_A, 'a', KeyEvent.CTRL_DOWN_MASK)
        backspace()
    }
}
