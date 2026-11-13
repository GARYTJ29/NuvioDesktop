package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.features.gamepad.ControllerSettings
import com.nuvio.app.features.gamepad.DesktopControllerSettings
import com.nuvio.app.features.gamepad.XInput
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_advanced_section_controller
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
