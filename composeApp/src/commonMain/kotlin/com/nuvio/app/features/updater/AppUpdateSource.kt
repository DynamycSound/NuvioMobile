package com.nuvio.app.features.updater

import kotlinx.coroutines.flow.StateFlow

/**
 * Where in-app updates come from. Android and iOS keep these defaults; the Windows build of the
 * DynamycSound/NuvioMobile fork points them at the fork's releases and MSI installers at startup.
 */
internal object AppUpdateSource {
    var repository: String = "NuvioMedia/NuvioMobile"

    var isInstallerAsset: (name: String, contentType: String?) -> Boolean = { name, contentType ->
        name.endsWith(".apk", ignoreCase = true) || contentType == "application/vnd.android.package-archive"
    }

    /** Set where updates can install themselves (Windows); null keeps the setting hidden. */
    var autoUpdate: AutoUpdateSetting? = null
}

/** "Install updates automatically": a launch check downloads in the background and only asks to restart. */
internal interface AutoUpdateSetting {
    val enabled: StateFlow<Boolean>

    fun setEnabled(enabled: Boolean)
}
