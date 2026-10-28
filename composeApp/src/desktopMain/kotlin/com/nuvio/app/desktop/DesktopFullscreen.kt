package com.nuvio.app.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD.LONG_PTR
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import java.awt.Window

/**
 * Borderless fullscreen on Windows: the window loses its title bar and frame and covers its whole
 * monitor, then gets its exact previous style and placement back (maximized or not). Compose's
 * WindowPlacement.Fullscreen kept the title bar on a decorated window (2026-10-27).
 *
 * Call on the AWT event thread.
 */
internal object DesktopFullscreen {
    private class Saved(val style: LONG_PTR, val placement: WinUser.WINDOWPLACEMENT)

    private var saved: Saved? = null

    val isFullscreen: Boolean get() = saved != null

    fun set(window: Window, enabled: Boolean) {
        if (enabled == isFullscreen || !window.isDisplayable) return
        val user32 = User32.INSTANCE
        val hwnd = HWND(Native.getComponentPointer(window))
        if (enabled) {
            val placement = WinUser.WINDOWPLACEMENT()
            user32.GetWindowPlacement(hwnd, placement)
            val style = user32.GetWindowLongPtr(hwnd, WinUser.GWL_STYLE)
            val monitorInfo = WinUser.MONITORINFO()
            user32.GetMonitorInfo(user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST), monitorInfo)
            saved = Saved(style, placement)
            // A maximized window keeps to the work area, so it is restored before it grows.
            if (placement.showCmd == WinUser.SW_SHOWMAXIMIZED) user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
            val frame = (WinUser.WS_CAPTION or WinUser.WS_THICKFRAME).toLong()
            user32.SetWindowLongPtr(hwnd, WinUser.GWL_STYLE, Pointer(style.toLong() and frame.inv()))
            val screen = monitorInfo.rcMonitor
            user32.SetWindowPos(
                hwnd, null, screen.left, screen.top, screen.right - screen.left, screen.bottom - screen.top,
                WinUser.SWP_NOOWNERZORDER or WinUser.SWP_FRAMECHANGED,
            )
        } else {
            val previous = saved ?: return
            saved = null
            user32.SetWindowLongPtr(hwnd, WinUser.GWL_STYLE, Pointer(previous.style.toLong()))
            user32.SetWindowPlacement(hwnd, previous.placement)
            user32.SetWindowPos(
                hwnd, null, 0, 0, 0, 0,
                WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or WinUser.SWP_NOOWNERZORDER or
                    WinUser.SWP_FRAMECHANGED,
            )
        }
    }
}
