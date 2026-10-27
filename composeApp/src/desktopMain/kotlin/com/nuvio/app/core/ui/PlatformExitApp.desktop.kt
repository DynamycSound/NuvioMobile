package com.nuvio.app.core.ui

import com.nuvio.app.desktop.compat.DesktopPreferences
import kotlin.system.exitProcess

actual fun platformExitApp() {
    DesktopPreferences.flushAll()
    exitProcess(0)
}
