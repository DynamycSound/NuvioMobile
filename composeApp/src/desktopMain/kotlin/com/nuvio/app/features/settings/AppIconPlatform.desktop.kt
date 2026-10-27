package com.nuvio.app.features.settings

// Alternate launcher icons are an Android/iOS feature; the Windows build always uses the default.
internal actual object AppIconPlatform {
    actual val requiresCloseConfirmation: Boolean = false

    actual fun currentIconName(): String? = null

    actual suspend fun activateIcon(name: String?): Boolean = name == null
}
