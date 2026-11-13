package com.nuvio.app.core.input

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

/**
 * Hover state that ignores a parked mouse pointer while a controller is driving
 * the UI. Scrolling with the pad slides cards under a still pointer, which would
 * otherwise light up whichever card happens to land there; the hover comes back
 * as soon as the mouse really moves.
 */
@Composable
fun InteractionSource.collectIsPointerHoveredAsState(): State<Boolean> {
    val hovered = collectIsHoveredAsState()
    val directional = AppNavigationBridge.isDirectionalInputActive.collectAsState()
    return remember(hovered, directional) {
        derivedStateOf { hovered.value && !directional.value }
    }
}
