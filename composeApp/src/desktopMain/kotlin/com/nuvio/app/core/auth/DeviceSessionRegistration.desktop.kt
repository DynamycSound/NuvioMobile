package com.nuvio.app.core.auth

import java.net.InetAddress

internal actual fun currentDeviceClientMetadata(): DeviceClientMetadata {
    val hostName = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        ?: System.getenv("COMPUTERNAME")
    return DeviceClientMetadata(
        deviceName = hostName?.takeIf { it.isNotBlank() } ?: "Windows PC",
        platform = "${System.getProperty("os.name")} (Desktop)",
    )
}
