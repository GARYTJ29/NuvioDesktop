package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.nuvio.app.controller.ControllerFocusZone
import com.nuvio.app.controller.RegisterControllerHandler
import com.nuvio.app.controller.controllerFocusable
import com.nuvio.app.controller.isControllerNodeFocused
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.isDesktop
import kotlinx.coroutines.launch

data class NuvioDropdownOption(
    val key: String,
    val label: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuvioDropdownChip(
    title: String,
    label: String,
    selectedKey: String?,
    options: List<NuvioDropdownOption>,
    enabled: Boolean = true,
    onSelected: (NuvioDropdownOption) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    /**
     * Set on screens the controller's spatial engine drives (desktop tab screens): the chip becomes
     * an engine stop, and its open menu is walked with the d-pad. Chips sharing [controllerGroupKey]
     * form one row.
     */
    controllerFocusId: String? = null,
    controllerGroupKey: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    var isSheetVisible by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val controllerDriven = isDesktop && controllerFocusId != null

    Box(modifier = modifier) {
        Row(
            modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .then(
                    if (controllerDriven) {
                        Modifier.controllerFocusable(
                            id = controllerFocusId!!,
                            groupKey = controllerGroupKey,
                            enabled = enabled,
                            shape = tokens.shapes.compactCard,
                            selectLabel = title,
                            onSelect = { isSheetVisible = true },
                        )
                    } else {
                        Modifier
                    },
                )
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.surface)
                .then(
                    if (enabled) {
                        Modifier.clickable { isSheetVisible = true }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = NuvioTokens.Space.s12, vertical = tokens.components.chipVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) tokens.colors.textPrimary else tokens.colors.textDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (fillWidth) Modifier.weight(1f) else Modifier,
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(NuvioTokens.Icon.sm + NuvioTokens.Space.s2),
                tint = if (enabled) tokens.colors.textMuted else tokens.colors.borderDefault,
            )
        }

        if (isDesktop) {
            DropdownMenu(
                expanded = isSheetVisible,
                onDismissRequest = { isSheetVisible = false },
                modifier = Modifier.platformTouchScroll().widthIn(min = 180.dp, max = 360.dp),
                containerColor = tokens.colors.surfacePopover,
                shape = tokens.shapes.compactCard,
            ) {
                // Controller: the open menu is one overlay stop. Up/Down move a highlight (the
                // menu scrolls to it), A picks it, B closes the menu. Options scrolled out of the
                // menu have no on-screen bounds, so they cannot be engine stops of their own.
                var highlighted by remember(isSheetVisible) {
                    mutableIntStateOf(options.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0))
                }
                val itemRequesters = remember(options.size) { List(options.size) { BringIntoViewRequester() } }
                val menuFocusId = "${controllerFocusId}_menu"
                val menuFocused = controllerDriven && isControllerNodeFocused(menuFocusId)
                if (controllerDriven && isSheetVisible) {
                    RegisterControllerHandler(
                        key = menuFocusId,
                        onBack = {
                            isSheetVisible = false
                            true
                        },
                        onBumperLeft = { true },
                        onBumperRight = { true },
                    )
                    LaunchedEffect(highlighted) {
                        itemRequesters.getOrNull(highlighted)?.bringIntoView()
                    }
                }
                Column(
                    modifier = if (controllerDriven) {
                        Modifier.controllerFocusable(
                            id = menuFocusId,
                            zone = ControllerFocusZone.Overlay,
                            hideFocusRing = true,
                            selectLabel = "Select",
                            onMoveUp = {
                                highlighted = (highlighted - 1).coerceAtLeast(0)
                                true
                            },
                            onMoveDown = {
                                highlighted = (highlighted + 1).coerceAtMost(options.lastIndex)
                                true
                            },
                            onMoveLeft = { true },
                            onMoveRight = { true },
                            onSelect = {
                                options.getOrNull(highlighted)?.let(onSelected)
                                isSheetVisible = false
                            },
                        )
                    } else {
                        Modifier
                    },
                ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        modifier = Modifier
                            .bringIntoViewRequester(itemRequesters[index])
                            .then(
                                if (menuFocused && index == highlighted) {
                                    Modifier.background(tokens.colors.textPrimary.copy(alpha = 0.14f))
                                } else {
                                    Modifier
                                },
                            ),
                        text = {
                            Text(
                                text = option.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = {
                            if (option.key == selectedKey) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = tokens.colors.accent,
                                    modifier = Modifier.size(tokens.icons.md),
                                )
                            }
                        },
                        onClick = {
                            onSelected(option)
                            isSheetVisible = false
                        },
                    )
                }
                }
            }
        }
    }

    if (isSheetVisible && !isDesktop) {
        NuvioDropdownOptionsSheet(
            title = title,
            options = options,
            selectedKey = selectedKey,
            sheetState = sheetState,
            onDismiss = {
                coroutineScope.launch {
                    dismissNuvioBottomSheet(
                        sheetState = sheetState,
                        onDismiss = { isSheetVisible = false },
                    )
                }
            },
            onSelected = { option ->
                onSelected(option)
                coroutineScope.launch {
                    dismissNuvioBottomSheet(
                        sheetState = sheetState,
                        onDismiss = { isSheetVisible = false },
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NuvioDropdownOptionsSheet(
    title: String,
    options: List<NuvioDropdownOption>,
    selectedKey: String?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSelected: (NuvioDropdownOption) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    NuvioModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(tokens.spacing.screenHorizontal)),
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = tokens.spacing.screenHorizontal, vertical = NuvioTokens.Space.s14),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
            )
            NuvioBottomSheetDivider()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = tokens.breakpoints.largePhone),
            ) {
                items(options) { option ->
                    NuvioBottomSheetActionRow(
                        title = option.label,
                        onClick = { onSelected(option) },
                        selected = option.key == selectedKey,
                        trailingContent = {
                            if (option.key == selectedKey) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = tokens.colors.accent,
                                    modifier = Modifier.size(tokens.icons.md),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
