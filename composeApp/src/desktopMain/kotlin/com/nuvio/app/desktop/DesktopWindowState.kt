package com.nuvio.app.desktop

import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.EventQueue
import java.awt.Frame

/** Process-wide handle on the main window, for platform code that has no composition. */
object DesktopWindowState {
    private val visibleState = MutableStateFlow(true)
    private val fullscreenState = MutableStateFlow(false)

    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    /** Requested fullscreen state; Main applies it to the window placement. */
    val fullscreen: StateFlow<Boolean> = fullscreenState.asStateFlow()

    @Volatile
    var window: ComposeWindow? = null
        internal set

    internal fun setVisible(visible: Boolean) {
        visibleState.value = visible
    }

    fun setFullscreen(enabled: Boolean) {
        fullscreenState.value = enabled
    }

    fun toggleFullscreen() = setFullscreen(!fullscreenState.value)

    fun bringToFront() {
        val window = window ?: return
        EventQueue.invokeLater {
            if (window.extendedState and Frame.ICONIFIED != 0) {
                window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
            }
            window.isVisible = true
            window.toFront()
            window.requestFocus()
        }
    }
}
