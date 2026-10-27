package com.nuvio.app.desktop

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type

/**
 * Routes window key presses to the player while one is on screen. The shared player screen has
 * no keyboard handling, since phones have no keyboard; the window hands keys here first.
 */
internal object DesktopPlayerKeys {
    fun interface Handler {
        fun onKey(event: KeyEvent): Boolean
    }

    @Volatile var active: Handler? = null

    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return active?.onKey(event) == true
    }
}
