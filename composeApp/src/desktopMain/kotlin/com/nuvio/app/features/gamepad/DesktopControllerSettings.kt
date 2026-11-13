package com.nuvio.app.features.gamepad

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ControllerSettings(
    val enabled: Boolean = true,
    val focusNavigation: Boolean = true,
    val virtualKeyboard: Boolean = true,
    val deadzonePercent: Int = DEFAULT_DEADZONE,
    val repeatDelayMs: Int = DEFAULT_REPEAT_DELAY,
    val repeatRateMs: Int = DEFAULT_REPEAT_RATE,
) {
    val deadzone: Float get() = deadzonePercent / 100f

    companion object {
        const val DEFAULT_DEADZONE = 35
        const val DEFAULT_REPEAT_DELAY = 500
        const val DEFAULT_REPEAT_RATE = 300
        val DeadzoneOptions = listOf(20, 35, 50)
        val RepeatDelayOptions = listOf(300, 500, 800)
        val RepeatRateOptions = listOf(150, 300, 500)
    }
}

/** The [Controller] section of the Stremio shell's settings.ini, kept as a desktop store. */
internal object DesktopControllerSettings {
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FOCUS_NAVIGATION = "focus_navigation"
    private const val KEY_VIRTUAL_KEYBOARD = "virtual_keyboard"
    private const val KEY_DEADZONE = "deadzone_percent"
    private const val KEY_REPEAT_DELAY = "repeat_delay_ms"
    private const val KEY_REPEAT_RATE = "repeat_rate_ms"

    private val store by lazy { DesktopStorage.store("nuvio_controller") }

    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<ControllerSettings> = mutableState.asStateFlow()

    val current: ControllerSettings get() = mutableState.value

    private fun load(): ControllerSettings = runCatching {
        ControllerSettings(
            enabled = store.getBoolean(KEY_ENABLED) ?: true,
            focusNavigation = store.getBoolean(KEY_FOCUS_NAVIGATION) ?: true,
            virtualKeyboard = store.getBoolean(KEY_VIRTUAL_KEYBOARD) ?: true,
            // Same clamps as the shell's config.cpp.
            deadzonePercent = (store.getInt(KEY_DEADZONE) ?: ControllerSettings.DEFAULT_DEADZONE).coerceIn(5, 90),
            repeatDelayMs = (store.getInt(KEY_REPEAT_DELAY) ?: ControllerSettings.DEFAULT_REPEAT_DELAY).coerceIn(50, 2000),
            repeatRateMs = (store.getInt(KEY_REPEAT_RATE) ?: ControllerSettings.DEFAULT_REPEAT_RATE).coerceIn(20, 1000),
        )
    }.getOrDefault(ControllerSettings())

    fun setEnabled(value: Boolean) = edit { store.putBoolean(KEY_ENABLED, value); it.copy(enabled = value) }

    fun setFocusNavigation(value: Boolean) =
        edit { store.putBoolean(KEY_FOCUS_NAVIGATION, value); it.copy(focusNavigation = value) }

    fun setVirtualKeyboard(value: Boolean) =
        edit { store.putBoolean(KEY_VIRTUAL_KEYBOARD, value); it.copy(virtualKeyboard = value) }

    fun setDeadzonePercent(value: Int) =
        edit { store.putInt(KEY_DEADZONE, value); it.copy(deadzonePercent = value) }

    fun setRepeatDelayMs(value: Int) =
        edit { store.putInt(KEY_REPEAT_DELAY, value); it.copy(repeatDelayMs = value) }

    fun setRepeatRateMs(value: Int) =
        edit { store.putInt(KEY_REPEAT_RATE, value); it.copy(repeatRateMs = value) }

    private inline fun edit(crossinline block: (ControllerSettings) -> ControllerSettings) {
        mutableState.update { block(it) }
    }
}
