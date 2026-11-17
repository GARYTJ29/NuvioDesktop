package com.nuvio.app.features.gamepad

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ControllerSettings(
    val enabled: Boolean = true,
    val virtualKeyboard: Boolean = true,
)

/** The [Controller] section of the Stremio shell's settings.ini, kept as a desktop store. */
internal object DesktopControllerSettings {
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VIRTUAL_KEYBOARD = "virtual_keyboard"

    private val store by lazy { DesktopStorage.store("nuvio_controller") }

    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<ControllerSettings> = mutableState.asStateFlow()

    val current: ControllerSettings get() = mutableState.value

    private fun load(): ControllerSettings = runCatching {
        ControllerSettings(
            enabled = store.getBoolean(KEY_ENABLED) ?: true,
            virtualKeyboard = store.getBoolean(KEY_VIRTUAL_KEYBOARD) ?: true,
        )
    }.getOrDefault(ControllerSettings())

    fun setEnabled(value: Boolean) = edit { store.putBoolean(KEY_ENABLED, value); it.copy(enabled = value) }

    fun setVirtualKeyboard(value: Boolean) =
        edit { store.putBoolean(KEY_VIRTUAL_KEYBOARD, value); it.copy(virtualKeyboard = value) }

    private inline fun edit(crossinline block: (ControllerSettings) -> ControllerSettings) {
        mutableState.update { block(it) }
    }
}
