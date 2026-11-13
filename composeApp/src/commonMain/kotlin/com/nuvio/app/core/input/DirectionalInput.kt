package com.nuvio.app.core.input

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

enum class NavigationDirection { Up, Down, Left, Right }

/**
 * Lets the focused item claim a direction before it moves focus, from the
 * keyboard's arrow keys and from the controller's d-pad alike. The pad moves
 * Compose focus directly rather than through key events, so it asks
 * [AppNavigationBridge.dispatchDirectionalInput] first.
 *
 * Apply it before the focus target it belongs to. [handler] returns true when
 * it used the direction, which then moves focus no further.
 */
fun Modifier.onDirectionalInput(handler: (NavigationDirection) -> Boolean): Modifier = composed {
    val latestHandler = rememberUpdatedState(handler)
    val registration = remember { DirectionalInputRegistration { latestHandler.value(it) } }
    DisposableEffect(registration) {
        onDispose { AppNavigationBridge.clearDirectionalHandler(registration) }
    }
    this
        .onFocusChanged { state ->
            if (state.hasFocus) {
                AppNavigationBridge.setDirectionalHandler(registration)
            } else {
                AppNavigationBridge.clearDirectionalHandler(registration)
            }
        }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val direction = when (event.key) {
                Key.DirectionUp -> NavigationDirection.Up
                Key.DirectionDown -> NavigationDirection.Down
                Key.DirectionLeft -> NavigationDirection.Left
                Key.DirectionRight -> NavigationDirection.Right
                else -> return@onPreviewKeyEvent false
            }
            latestHandler.value(direction)
        }
}

internal class DirectionalInputRegistration(val handle: (NavigationDirection) -> Boolean)
