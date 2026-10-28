package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.desktop.DesktopPower
import com.nuvio.app.desktop.DesktopWindowState
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

@Composable
actual fun LockPlayerToLandscape() = Unit

@Composable
actual fun FullscreenPlayerDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        content = content,
    )
}

@Composable
actual fun HidePlayerSystemBars() = Unit

/** The desktop counterpart of Android's immersive mode: fullscreen window, no screen sleep. */
@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    DisposableEffect(Unit) {
        val wasFullscreen = DesktopWindowState.fullscreen.value
        DesktopWindowState.setFullscreen(true)
        onDispose {
            if (!wasFullscreen) DesktopWindowState.setFullscreen(false)
        }
    }
    DisposableEffect(keepScreenAwake) {
        if (keepScreenAwake) DesktopPower.acquire()
        onDispose {
            if (keepScreenAwake) DesktopPower.release()
        }
    }
}

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    videoSize: IntSize,
) = Unit

@Composable
actual fun rememberIsInPictureInPicture(): Boolean = false

// Brightness/volume swipe gestures are a touch feature; desktop uses keyboard and wheel controls.
@Composable
internal actual fun rememberPlatformPlayerGestureController(): PlayerGestureController? = null

/** Desktop players hide the mouse pointer along with the controls; moving the mouse brings both back. */
@Composable
internal actual fun rememberHiddenPlayerPointerIcon(): PointerIcon? = remember {
    val blank = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(blank, Point(0, 0), "hidden"))
}
