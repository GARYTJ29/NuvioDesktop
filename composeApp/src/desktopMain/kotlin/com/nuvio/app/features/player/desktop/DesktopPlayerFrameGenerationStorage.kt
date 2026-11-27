package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage

/**
 * DLSS frame generation multiplier chosen with the player's FG button
 * (0 = off, 2..4). Kept per machine, not synced: it depends on this PC's GPU.
 */
internal object DesktopPlayerFrameGenerationStorage {
    private const val MultiplierKey = "frame_generation_multiplier"
    private const val MaxMultiplier = 4

    private val store = DesktopStorage.store("nuvio_player_runtime")

    fun loadMultiplier(): Int = store.getInt(MultiplierKey)?.coerceFrameGenerationMultiplier() ?: 0

    fun saveMultiplier(multiplier: Int) {
        store.putInt(MultiplierKey, multiplier.coerceFrameGenerationMultiplier())
    }

    private fun Int.coerceFrameGenerationMultiplier(): Int =
        if (this < 2) 0 else coerceAtMost(MaxMultiplier)
}
