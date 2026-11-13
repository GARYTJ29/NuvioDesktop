package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.features.gamepad.ControllerSettings
import com.nuvio.app.features.gamepad.DesktopControllerSettings
import com.nuvio.app.features.gamepad.XInput
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.power.IdleScope
import com.nuvio.app.features.power.PowerAction
import com.nuvio.app.features.power.PowerMenu
import com.nuvio.app.features.power.PowerSettings
import com.nuvio.app.features.power.PowerSettingsStore
import com.nuvio.app.features.power.label
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_advanced_section_controller
import nuvio.composeapp.generated.resources.settings_advanced_section_power
import nuvio.composeapp.generated.resources.settings_power_idle
import nuvio.composeapp.generated.resources.settings_power_idle_action
import nuvio.composeapp.generated.resources.settings_power_idle_action_description
import nuvio.composeapp.generated.resources.settings_power_idle_description
import nuvio.composeapp.generated.resources.settings_power_idle_minutes
import nuvio.composeapp.generated.resources.settings_power_idle_minutes_description
import nuvio.composeapp.generated.resources.settings_power_idle_prompt
import nuvio.composeapp.generated.resources.settings_power_idle_prompt_description
import nuvio.composeapp.generated.resources.settings_power_idle_scope
import nuvio.composeapp.generated.resources.settings_power_idle_scope_description
import nuvio.composeapp.generated.resources.settings_power_idle_scope_everywhere
import nuvio.composeapp.generated.resources.settings_power_idle_scope_player
import nuvio.composeapp.generated.resources.settings_power_idle_scope_playing
import nuvio.composeapp.generated.resources.settings_power_menu
import nuvio.composeapp.generated.resources.settings_power_menu_description
import nuvio.composeapp.generated.resources.settings_controller_deadzone
import nuvio.composeapp.generated.resources.settings_controller_deadzone_description
import nuvio.composeapp.generated.resources.settings_controller_enabled
import nuvio.composeapp.generated.resources.settings_controller_enabled_description
import nuvio.composeapp.generated.resources.settings_controller_focus_navigation
import nuvio.composeapp.generated.resources.settings_controller_focus_navigation_description
import nuvio.composeapp.generated.resources.settings_controller_repeat_delay
import nuvio.composeapp.generated.resources.settings_controller_repeat_delay_description
import nuvio.composeapp.generated.resources.settings_controller_repeat_rate
import nuvio.composeapp.generated.resources.settings_controller_repeat_rate_description
import nuvio.composeapp.generated.resources.settings_controller_virtual_keyboard
import nuvio.composeapp.generated.resources.settings_controller_virtual_keyboard_description
import org.jetbrains.compose.resources.stringResource

internal actual fun LazyListScope.platformAdvancedSettingsContent(isTablet: Boolean) {
    if (XInput.isAvailable) {
        item { ControllerSettingsSection(isTablet) }
    }
    if (DesktopHostOs.current == DesktopHostOs.WINDOWS) {
        item { PowerSettingsSection(isTablet) }
    }
}

@Composable
private fun PowerSettingsSection(isTablet: Boolean) {
    val settings by PowerSettingsStore.state.collectAsState()
    val idle = settings.idleEnabled
    SettingsSection(
        title = stringResource(Res.string.settings_advanced_section_power),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_power_menu),
                description = stringResource(Res.string.settings_power_menu_description),
                isTablet = isTablet,
                onClick = { PowerMenu.toggle() },
            )
            SettingsGroupDivider(isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_power_idle),
                description = stringResource(Res.string.settings_power_idle_description),
                checked = idle,
                isTablet = isTablet,
                onCheckedChange = PowerSettingsStore::setIdleEnabled,
            )
            SettingsGroupDivider(isTablet)
            SettingsChipRow(
                title = stringResource(Res.string.settings_power_idle_scope),
                description = stringResource(Res.string.settings_power_idle_scope_description),
                isTablet = isTablet,
            ) {
                IdleScope.entries.forEach { scope ->
                    Chip(
                        label = stringResource(
                            when (scope) {
                                IdleScope.Playing -> Res.string.settings_power_idle_scope_playing
                                IdleScope.Player -> Res.string.settings_power_idle_scope_player
                                IdleScope.Everywhere -> Res.string.settings_power_idle_scope_everywhere
                            },
                        ),
                        selected = settings.idleScope == scope,
                        enabled = idle,
                        onClick = { PowerSettingsStore.setIdleScope(scope) },
                    )
                }
            }
            SettingsGroupDivider(isTablet)
            ChoiceRow(
                title = stringResource(Res.string.settings_power_idle_minutes),
                description = stringResource(Res.string.settings_power_idle_minutes_description),
                isTablet = isTablet,
                options = PowerSettings.IdleMinuteOptions,
                selected = settings.idleMinutes,
                enabled = idle,
                label = { "$it min" },
                onSelect = PowerSettingsStore::setIdleMinutes,
            )
            SettingsGroupDivider(isTablet)
            ChoiceRow(
                title = stringResource(Res.string.settings_power_idle_prompt),
                description = stringResource(Res.string.settings_power_idle_prompt_description),
                isTablet = isTablet,
                options = PowerSettings.IdlePromptSecondOptions,
                selected = settings.idlePromptSeconds,
                enabled = idle,
                label = { "$it s" },
                onSelect = PowerSettingsStore::setIdlePromptSeconds,
            )
            SettingsGroupDivider(isTablet)
            SettingsChipRow(
                title = stringResource(Res.string.settings_power_idle_action),
                description = stringResource(Res.string.settings_power_idle_action_description),
                isTablet = isTablet,
            ) {
                PowerAction.entries.forEach { action ->
                    Chip(
                        label = stringResource(action.label),
                        selected = settings.idleAction == action,
                        enabled = idle,
                        onClick = { PowerSettingsStore.setIdleAction(action) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ControllerSettingsSection(isTablet: Boolean) {
    val settings by DesktopControllerSettings.state.collectAsState()
    SettingsSection(
        title = stringResource(Res.string.settings_advanced_section_controller),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_controller_enabled),
                description = stringResource(Res.string.settings_controller_enabled_description),
                checked = settings.enabled,
                isTablet = isTablet,
                onCheckedChange = DesktopControllerSettings::setEnabled,
            )
            SettingsGroupDivider(isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_controller_focus_navigation),
                description = stringResource(Res.string.settings_controller_focus_navigation_description),
                checked = settings.focusNavigation,
                enabled = settings.enabled,
                isTablet = isTablet,
                onCheckedChange = DesktopControllerSettings::setFocusNavigation,
            )
            SettingsGroupDivider(isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_controller_virtual_keyboard),
                description = stringResource(Res.string.settings_controller_virtual_keyboard_description),
                checked = settings.virtualKeyboard,
                enabled = settings.enabled,
                isTablet = isTablet,
                onCheckedChange = DesktopControllerSettings::setVirtualKeyboard,
            )
            SettingsGroupDivider(isTablet)
            ChoiceRow(
                title = stringResource(Res.string.settings_controller_deadzone),
                description = stringResource(Res.string.settings_controller_deadzone_description),
                isTablet = isTablet,
                options = ControllerSettings.DeadzoneOptions,
                selected = settings.deadzonePercent,
                enabled = settings.enabled,
                label = { "$it%" },
                onSelect = DesktopControllerSettings::setDeadzonePercent,
            )
            SettingsGroupDivider(isTablet)
            ChoiceRow(
                title = stringResource(Res.string.settings_controller_repeat_delay),
                description = stringResource(Res.string.settings_controller_repeat_delay_description),
                isTablet = isTablet,
                options = ControllerSettings.RepeatDelayOptions,
                selected = settings.repeatDelayMs,
                enabled = settings.enabled,
                label = { "$it ms" },
                onSelect = DesktopControllerSettings::setRepeatDelayMs,
            )
            SettingsGroupDivider(isTablet)
            ChoiceRow(
                title = stringResource(Res.string.settings_controller_repeat_rate),
                description = stringResource(Res.string.settings_controller_repeat_rate_description),
                isTablet = isTablet,
                options = ControllerSettings.RepeatRateOptions,
                selected = settings.repeatRateMs,
                enabled = settings.enabled,
                label = { "$it ms" },
                onSelect = DesktopControllerSettings::setRepeatRateMs,
            )
        }
    }
}

@Composable
internal fun ChoiceRow(
    title: String,
    description: String,
    isTablet: Boolean,
    options: List<Int>,
    selected: Int,
    enabled: Boolean,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    SettingsChipRow(
        title = title,
        description = description,
        isTablet = isTablet,
    ) {
        options.forEach { option ->
            Chip(
                label = label(option),
                selected = selected == option,
                enabled = enabled,
                onClick = { onSelect(option) },
            )
        }
    }
}
