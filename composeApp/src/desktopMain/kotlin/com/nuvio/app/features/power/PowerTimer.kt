package com.nuvio.app.features.power

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ArmedPowerTimer(val action: PowerAction, val firesAtMillis: Long)

internal sealed interface PowerTimerEvent {
    data class Armed(val timer: ArmedPowerTimer) : PowerTimerEvent
    data object Cancelled : PowerTimerEvent
}

/**
 * The sleep timer: one pending action at a time, for this session only, as in
 * the shell. Unlike the shell it lives outside any web page, so nothing short of
 * quitting the app drops it.
 */
internal object PowerTimer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val armedState = MutableStateFlow<ArmedPowerTimer?>(null)
    val armed: StateFlow<ArmedPowerTimer?> = armedState.asStateFlow()

    private val eventFlow = MutableSharedFlow<PowerTimerEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PowerTimerEvent> = eventFlow

    /** Runs [action] after [minutes]; zero runs it now. Replaces any armed timer. */
    fun arm(action: PowerAction, minutes: Int) {
        cancel(announce = false)
        if (minutes <= 0) {
            PowerActions.run(action)
            return
        }
        val firesAt = System.currentTimeMillis() + minutes * 60_000L
        val timer = ArmedPowerTimer(action, firesAt)
        armedState.value = timer
        eventFlow.tryEmit(PowerTimerEvent.Armed(timer))
        job = scope.launch {
            delay(firesAt - System.currentTimeMillis())
            armedState.value = null
            job = null
            PowerActions.run(action)
        }
    }

    fun cancel(announce: Boolean = true) {
        val wasArmed = armedState.value != null
        job?.cancel()
        job = null
        armedState.value = null
        if (announce && wasArmed) eventFlow.tryEmit(PowerTimerEvent.Cancelled)
    }
}
