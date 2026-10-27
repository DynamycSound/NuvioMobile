package com.nuvio.app.desktop

import com.nuvio.app.desktop.compat.DesktopPreferences
import com.nuvio.app.features.updater.AppUpdateSource
import com.nuvio.app.features.updater.AutoUpdateSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Points the shared updater at this fork: its GitHub Actions merge upstream every week and publish
 * an MSI under the same tag as the upstream release (see .github/workflows/desktop-sync.yml).
 */
internal fun configureDesktopUpdates() {
    AppUpdateSource.repository = DesktopBuildConfig.UPDATE_REPO
    AppUpdateSource.isInstallerAsset = { name, _ -> name.endsWith(".msi", ignoreCase = true) }
    AppUpdateSource.autoUpdate = DesktopAutoUpdate
}

/** On by default: updates download at launch and the app only asks to restart. */
internal object DesktopAutoUpdate : AutoUpdateSetting {
    private const val KEY = "auto_install"
    private val prefs by lazy { DesktopPreferences.open("app_updater") }
    private val state by lazy { MutableStateFlow(prefs.getBoolean(KEY, true)) }

    override val enabled: StateFlow<Boolean> get() = state.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
        state.value = enabled
    }
}
