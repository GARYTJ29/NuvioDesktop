package com.nuvio.app.features.power

import co.touchlab.kermit.Logger
import com.nuvio.app.features.gamepad.GamepadRouter
import com.nuvio.app.features.player.desktop.ActiveDesktopPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Dialog
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * "Are you still there?": playback keeps Windows from sleeping on its own, so
 * someone who dozes off mid-episode would otherwise play on all night. After
 * a stretch with no input in scope, this asks, and if nobody answers within
 * the prompt's countdown it runs the chosen action. A port of the shell's
 * power-menu.js idle watchdog.
 *
 * `-Dnuvio.power.idleSeconds=N` shortens the wait to N seconds, for testing.
 */
internal object IdleWatchdog {
    private const val TICK_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val log = Logger.withTag("Power")

    @Volatile
    private var lastActivity = System.currentTimeMillis()
    private var lastPointer: Point? = null

    fun noteActivity() {
        lastActivity = System.currentTimeMillis()
    }

    /** Input from someone at the app, outside the prompt: that answers the prompt too. */
    private fun notePresence() {
        noteActivity()
        if (PowerMenu.mode == PowerMode.Idle) {
            log.i { "idle prompt answered by input" }
            SwingUtilities.invokeLater { PowerMenu.close() }
        }
    }

    /** Starts listening; returns the stopper. */
    fun start(): () -> Unit {
        val awtListener = AWTEventListener { event ->
            // The prompt's own window is navigated, not dismissed, by its input.
            val component = event.source as? Component
            val window = component as? Window ?: component?.let(SwingUtilities::getWindowAncestor)
            // Windows reports a "move" whenever a window appears or goes away
            // under a still pointer (the prompt itself does), and entered/exited
            // likewise; only a pointer that really moved, a press, the wheel or
            // a key counts.
            val deliberate = when (event.id) {
                MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED -> {
                    val at = (event as MouseEvent).locationOnScreen
                    val moved = at != lastPointer
                    lastPointer = at
                    moved
                }
                MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_WHEEL, KeyEvent.KEY_PRESSED -> true
                else -> false
            }
            when {
                !deliberate -> Unit
                window is Dialog -> noteActivity()
                else -> notePresence()
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(
            awtListener,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or
                AWTEvent.MOUSE_WHEEL_EVENT_MASK or AWTEvent.KEY_EVENT_MASK,
        )
        val removeGamepad = GamepadRouter.addActivityListener(::noteActivity)
        ActiveDesktopPlayer.onUserActivity = ::notePresence
        PowerMenu.onClosed = ::noteActivity
        noteActivity()
        job = scope.launch {
            while (isActive) {
                // The prompt's own countdown wants a finer tick than the check.
                if (PowerMenu.mode == PowerMode.Idle) {
                    PowerMenu.tickIdle()
                    delay(250)
                } else {
                    check()
                    delay(TICK_MS)
                }
            }
        }
        return {
            job?.cancel()
            job = null
            Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener)
            removeGamepad()
            ActiveDesktopPlayer.onUserActivity = null
            PowerMenu.onClosed = null
        }
    }

    private fun check() {
        val settings = PowerSettingsStore.current
        val now = System.currentTimeMillis()
        // Only time spent in scope counts, so a paused film or a disabled check
        // does not leave a prompt waiting the moment it comes back into play.
        if (!settings.idleEnabled || !inScope(settings.idleScope) || PowerMenu.mode != null) {
            lastActivity = now
            return
        }
        val limitMs = System.getProperty("nuvio.power.idleSeconds")?.toLongOrNull()?.times(1000)
            ?: settings.idleMinutes * 60_000L
        if (now - lastActivity >= limitMs) {
            log.i { "no input for ${(now - lastActivity) / 1000}s, asking before ${settings.idleAction.id}" }
            PowerMenu.openIdlePrompt(settings.idleAction, settings.idlePromptSeconds)
        }
    }

    private fun inScope(scope: IdleScope): Boolean {
        val player = ActiveDesktopPlayer.controller
        return when (scope) {
            IdleScope.Everywhere -> true
            IdleScope.Player -> player != null
            // An unknown pause state counts as playing, the safe side.
            IdleScope.Playing -> player != null && player.isPlaybackPaused != true
        }
    }
}
