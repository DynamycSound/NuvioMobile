package com.nuvio.app.desktop

import co.touchlab.kermit.Logger
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon

/**
 * Windows notifications ("toasts") through the AWT system tray icon. Clicking a notification
 * runs the action passed with it (usually opening a deep link and bringing the window forward).
 */
object DesktopTray {
    private val log = Logger.withTag("DesktopTray")
    private var trayIcon: TrayIcon? = null
    private var pendingAction: (() -> Unit)? = null

    val isSupported: Boolean get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    @Synchronized
    private fun icon(): TrayIcon? {
        trayIcon?.let { return it }
        if (!isSupported) return null
        return try {
            val image = Toolkit.getDefaultToolkit().getImage(DesktopTray::class.java.getResource("/icons/nuvio.png"))
            TrayIcon(image, "Nuvio").apply {
                isImageAutoSize = true
                addActionListener {
                    val action = pendingAction
                    pendingAction = null
                    action?.invoke() ?: DesktopWindowState.bringToFront()
                }
                SystemTray.getSystemTray().add(this)
            }.also { trayIcon = it }
        } catch (error: Exception) {
            log.w(error) { "System tray is not available" }
            null
        }
    }

    fun notify(title: String, message: String, onClick: (() -> Unit)? = null): Boolean {
        val icon = icon() ?: return false
        pendingAction = onClick
        icon.displayMessage(title, message, TrayIcon.MessageType.NONE)
        return true
    }

    @Synchronized
    fun remove() {
        trayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        trayIcon = null
    }
}
