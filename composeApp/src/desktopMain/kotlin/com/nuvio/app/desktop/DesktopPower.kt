package com.nuvio.app.desktop

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.Executors

/**
 * Keeps the display and system awake during playback (SetThreadExecutionState). The state is
 * owned by the calling thread, so every call goes through one long-lived thread.
 */
object DesktopPower {
    private interface Kernel32Power : StdCallLibrary {
        fun SetThreadExecutionState(flags: Int): Int
    }

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    private val kernel32: Kernel32Power? by lazy {
        runCatching { Native.load("kernel32", Kernel32Power::class.java) }.getOrNull()
    }
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "nuvio-power").apply { isDaemon = true } }
    private var holders = 0

    @Synchronized
    fun acquire() {
        if (holders++ == 0) apply(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED)
    }

    @Synchronized
    fun release() {
        if (holders == 0) return
        if (--holders == 0) apply(ES_CONTINUOUS)
    }

    private fun apply(flags: Int) {
        thread.execute { kernel32?.SetThreadExecutionState(flags) }
    }
}
