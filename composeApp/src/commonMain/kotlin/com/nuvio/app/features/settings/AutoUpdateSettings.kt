package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.app.features.updater.AppUpdateSource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_auto_install_description
import nuvio.composeapp.generated.resources.updates_auto_install_title
import org.jetbrains.compose.resources.stringResource

/** Only shown where updates can install themselves (the Windows build); nothing elsewhere. */
@Composable
internal fun AutoUpdateSettingsRow(isTablet: Boolean) {
    val setting = AppUpdateSource.autoUpdate ?: return
    val enabled by setting.enabled.collectAsState()
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.updates_auto_install_title),
        description = stringResource(Res.string.updates_auto_install_description),
        checked = enabled,
        isTablet = isTablet,
        onCheckedChange = setting::setEnabled,
    )
}
