package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Calls [onMove] when a mouse moves over the player, the way desktop players show their controls.
 * Touch is left to the tap gestures. Only real movement counts: Compose also sends moves when the
 * layout changes under a still pointer, which would keep the controls up forever.
 */
internal fun Modifier.onMouseMoved(onMove: () -> Unit): Modifier = pointerInput(Unit) {
    var last: Offset? = null
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Move) continue
            val change = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
            if (change.position != last) {
                if (last != null) onMove()
                last = change.position
            }
        }
    }
}
