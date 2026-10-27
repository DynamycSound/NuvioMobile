package com.nuvio.app.features.player

import com.nuvio.app.desktop.DesktopPaths

// ExoPlayer is Android-only; desktop playback runs on libmpv, which manages its own buffers.
internal actual fun applyExoPlayerNativeMemory(enabled: Boolean) = Unit

internal actual fun isExoNativeMemorySupported(): Boolean = false

internal actual fun exoNativeMemoryInfo(): ExoNativeMemoryInfo? = null

internal actual fun playbackHeapBufferMaxMb(): Int {
    val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
    val budget = (maxHeapMb * 0.85).toInt()
    return (budget / PlaybackBufferSettings.TARGET_STEP_MB * PlaybackBufferSettings.TARGET_STEP_MB)
        .coerceIn(PlaybackBufferSettings.MIN_TARGET_MB, 4_096)
}

internal actual fun playbackCacheUsableSpaceBytes(): Long =
    DesktopPaths.cacheDir.usableSpace.coerceAtLeast(0L)
