package com.nuvio.app.features.power

import com.nuvio.app.features.gamepad.GamepadButton
import com.nuvio.app.features.gamepad.GamepadPress
import com.nuvio.app.features.gamepad.GamepadRouter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerMenuTest {
    @AfterTest
    fun tearDown() {
        PowerMenu.close()
    }

    private fun openOnWhenRow() {
        PowerMenu.toggle()
        while (PowerMenu.selectedRow != PowerRow.When) PowerMenu.move(1)
    }

    @Test
    fun `menu opens on OK so a remembered choice is one press`() {
        PowerMenu.toggle()
        assertEquals(PowerMode.Menu, PowerMenu.mode)
        assertEquals(PowerRow.Ok, PowerMenu.selectedRow)
    }

    @Test
    fun `the shortcut toggles the menu closed`() {
        PowerMenu.toggle()
        PowerMenu.toggle()
        assertNull(PowerMenu.mode)
    }

    @Test
    fun `typed digits fill hours then minutes`() {
        openOnWhenRow()
        listOf(2, 3, 4, 5).forEach(PowerMenu::typeDigit)
        assertEquals(23, PowerMenu.hours)
        assertEquals(45, PowerMenu.minutes)
        assertTrue(PowerMenu.editing)
    }

    @Test
    fun `a second digit that overruns the hours starts a new value`() {
        openOnWhenRow()
        PowerMenu.typeDigit(2)
        PowerMenu.typeDigit(5)
        assertEquals(5, PowerMenu.hours)
    }

    @Test
    fun `up increases the value being edited and wraps`() {
        openOnWhenRow()
        PowerMenu.activate()
        PowerMenu.clearField()
        PowerMenu.onDirection(horizontal = false, dir = 1)
        assertEquals(23, PowerMenu.hours)
        PowerMenu.onDirection(horizontal = false, dir = -1)
        assertEquals(0, PowerMenu.hours)
    }

    @Test
    fun `a quick run on the minutes steps in fives`() {
        openOnWhenRow()
        PowerMenu.activate()
        PowerMenu.onDirection(horizontal = true, dir = 1)
        PowerMenu.clearField()
        repeat(5) { PowerMenu.onDirection(horizontal = false, dir = -1) }
        assertEquals(5, PowerMenu.minutes)
        PowerMenu.onDirection(horizontal = false, dir = -1)
        assertEquals(10, PowerMenu.minutes)
    }

    @Test
    fun `B leaves an edit before it closes the menu`() {
        openOnWhenRow()
        PowerMenu.activate()
        PowerMenu.back()
        assertFalse(PowerMenu.editing)
        assertEquals(PowerMode.Menu, PowerMenu.mode)
        PowerMenu.back()
        assertNull(PowerMenu.mode)
    }

    @Test
    fun `the open menu takes every gamepad press`() {
        PowerMenu.toggle()
        GamepadRouter.onPress(GamepadPress(GamepadButton.UP, repeat = false, lbHeld = false, rbHeld = false))
        assertEquals(PowerMode.Menu, PowerMenu.mode)
        GamepadRouter.onPress(GamepadPress(GamepadButton.B, repeat = false, lbHeld = false, rbHeld = false))
        assertNull(PowerMenu.mode)
    }

    @Test
    fun `any other button answers the idle prompt`() {
        PowerMenu.openIdlePrompt(PowerAction.Sleep, promptSeconds = 60)
        assertEquals(PowerMode.Idle, PowerMenu.mode)
        GamepadRouter.onPress(GamepadPress(GamepadButton.Y, repeat = false, lbHeld = false, rbHeld = false))
        assertNull(PowerMenu.mode)
    }

    @Test
    fun `remaining time reads like the shell's chip`() {
        assertEquals("1h 30m", formatRemaining(90 * 60_000L))
        assertEquals("45m", formatRemaining(45 * 60_000L))
        assertEquals("30s", formatRemaining(30_000L))
    }
}
