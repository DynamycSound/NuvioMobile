package com.nuvio.app

class DesktopPlatform : Platform {
    override val name: String = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
}

actual fun getPlatform(): Platform = DesktopPlatform()

internal actual val isIos: Boolean = false

internal actual val supportsPosterNavigationMotion: Boolean = true
