package com.nuvio.app.features.power

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class PowerAction(val id: String) {
    Quit("quit"),
    Sleep("sleep"),
    Shutdown("shutdown"),
    ;

    companion object {
        fun fromId(id: String?): PowerAction? = entries.firstOrNull { it.id == id }
    }
}

/** When the idle check runs. */
internal enum class IdleScope(val id: String) {
    /** Only while a video is playing: the case of falling asleep mid-episode. */
    Playing("playing"),

    /** Whenever the player is open, paused or not. */
    Player("player"),

    /** Anywhere in the app. */
    Everywhere("everywhere"),
    ;

    companion object {
        fun fromId(id: String?): IdleScope = entries.firstOrNull { it.id == id } ?: Playing
    }
}

internal data class PowerSettings(
    val lastAction: PowerAction = PowerAction.Quit,
    val lastDelayMinutes: Int = 0,
    val idleEnabled: Boolean = false,
    val idleScope: IdleScope = IdleScope.Playing,
    val idleMinutes: Int = 60,
    val idlePromptSeconds: Int = 60,
    val idleAction: PowerAction = PowerAction.Sleep,
) {
    companion object {
        val IdleMinuteOptions = listOf(30, 45, 60, 90, 120)
        val IdlePromptSecondOptions = listOf(30, 60, 120)
    }
}

/**
 * The power menu's remembered choice and the idle check's settings. The shell
 * kept these in the web UI's localStorage (kai-power-*, kai-idle-*).
 */
internal object PowerSettingsStore {
    private const val KEY_LAST_ACTION = "last_action"
    private const val KEY_LAST_DELAY = "last_delay_minutes"
    private const val KEY_IDLE_ENABLED = "idle_enabled"
    private const val KEY_IDLE_SCOPE = "idle_scope"
    private const val KEY_IDLE_MINUTES = "idle_minutes"
    private const val KEY_IDLE_PROMPT_SECONDS = "idle_prompt_seconds"
    private const val KEY_IDLE_ACTION = "idle_action"

    private val store by lazy { DesktopStorage.store("nuvio_power") }

    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<PowerSettings> = mutableState.asStateFlow()
    val current: PowerSettings get() = mutableState.value

    private fun load(): PowerSettings = runCatching {
        PowerSettings(
            lastAction = PowerAction.fromId(store.getString(KEY_LAST_ACTION)) ?: PowerAction.Quit,
            lastDelayMinutes = (store.getInt(KEY_LAST_DELAY) ?: 0).coerceIn(0, 24 * 60 - 1),
            idleEnabled = store.getBoolean(KEY_IDLE_ENABLED) ?: false,
            idleScope = IdleScope.fromId(store.getString(KEY_IDLE_SCOPE)),
            idleMinutes = store.getInt(KEY_IDLE_MINUTES) ?: 60,
            idlePromptSeconds = store.getInt(KEY_IDLE_PROMPT_SECONDS) ?: 60,
            idleAction = PowerAction.fromId(store.getString(KEY_IDLE_ACTION)) ?: PowerAction.Sleep,
        )
    }.getOrDefault(PowerSettings())

    fun rememberChoice(action: PowerAction, delayMinutes: Int) {
        store.putString(KEY_LAST_ACTION, action.id)
        store.putInt(KEY_LAST_DELAY, delayMinutes)
        mutableState.update { it.copy(lastAction = action, lastDelayMinutes = delayMinutes) }
    }

    fun setIdleEnabled(value: Boolean) {
        store.putBoolean(KEY_IDLE_ENABLED, value)
        mutableState.update { it.copy(idleEnabled = value) }
    }

    fun setIdleScope(value: IdleScope) {
        store.putString(KEY_IDLE_SCOPE, value.id)
        mutableState.update { it.copy(idleScope = value) }
    }

    fun setIdleMinutes(value: Int) {
        store.putInt(KEY_IDLE_MINUTES, value)
        mutableState.update { it.copy(idleMinutes = value) }
    }

    fun setIdlePromptSeconds(value: Int) {
        store.putInt(KEY_IDLE_PROMPT_SECONDS, value)
        mutableState.update { it.copy(idlePromptSeconds = value) }
    }

    fun setIdleAction(value: PowerAction) {
        store.putString(KEY_IDLE_ACTION, value.id)
        mutableState.update { it.copy(idleAction = value) }
    }
}
