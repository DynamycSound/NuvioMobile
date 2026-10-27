package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.disk.DiskCache
import com.nuvio.app.desktop.DesktopPaths
import okio.Path.Companion.toOkioPath
import java.io.File

// The shared loader from App.kt, with its disk cache placed under %LOCALAPPDATA%\Nuvio\cache.
internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder =
    diskCache {
        DiskCache.Builder()
            .directory(File(DesktopPaths.cacheDir, "images").toOkioPath())
            .maxSizeBytes(512L * 1024 * 1024)
            .build()
    }

internal actual val platformProvidesImageLoader: Boolean = false
