package com.nuvio.app.controller

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.nuvio.app.isDesktop

/**
 * Makes a plain clickable control (chip, icon button, list row) a stop for the controller's spatial
 * engine, the way posters and sidebar items are. Without one, the engine cannot see the control at
 * all, so the d-pad skips it and no focus ring is drawn.
 *
 * Opt-in: nothing happens when [id] is null, so shared components only register where a screen the
 * engine drives (the desktop tab screens) passes one. Chain it right before the control's
 * clickable. Controls sharing [groupKey] form one row for Left/Right; give each row its own key.
 *
 * Stops in a sticky header are on screen at every scroll position and would catch Up from deep in
 * the list below; pass [enabled] = false for them unless the list is near its top.
 */
fun Modifier.controllerStop(
    id: String?,
    groupKey: String? = null,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    selectLabel: String = "Select",
    isTextInput: Boolean = false,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    onSelect: () -> Unit,
): Modifier =
    if (id == null || !isDesktop) {
        this
    } else {
        controllerFocusable(
            id = id,
            groupKey = groupKey,
            enabled = enabled,
            shape = shape,
            selectLabel = selectLabel,
            isTextInput = isTextInput,
            secondaryActionLabel = secondaryActionLabel,
            onSecondaryAction = onSecondaryAction,
            onSelect = onSelect,
        )
    }
