package com.nuvio.app.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import com.nuvio.app.desktop.compat.DesktopPreferences
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * What happens when the UI throws. Compose's default shows the message and then closes the window
 * the normal way, but after an exception in layout the composition is broken (the next frame
 * fails with "layout state is not idle"), and that close could leave the process running with no
 * window. Instead: write the error to `%LOCALAPPDATA%\Nuvio\logs`, save settings, show one
 * message, and end the process.
 */
@OptIn(ExperimentalComposeUiApi::class)
object DesktopCrashHandler : WindowExceptionHandlerFactory {
    private val crashed = AtomicBoolean(false)

    override fun exceptionHandler(window: java.awt.Window) = WindowExceptionHandler { error -> crash(window, error) }

    /** Errors on background threads are only logged: they do not break the UI. */
    fun installThreadHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            writeLog(error, "Uncaught on thread ${thread.name}")
            previous?.uncaughtException(thread, error)
        }
    }

    private fun crash(window: java.awt.Window, error: Throwable) {
        val log = writeLog(error, "UI error")
        // Exceptions that follow the first come from the same broken frame; the log keeps them.
        if (!crashed.compareAndSet(false, true)) return
        runCatching { DesktopPreferences.flushAll() }
        runCatching { DesktopTray.remove() }
        SwingUtilities.invokeLater {
            val where = log?.let { "\n\nDetails were saved to:\n${it.absolutePath}" }.orEmpty()
            JOptionPane.showMessageDialog(
                window.takeIf { it.isDisplayable },
                "Nuvio ran into an error and has to close.\n\n${error.message ?: error::class.simpleName}$where",
                "Nuvio",
                JOptionPane.ERROR_MESSAGE,
            )
            exitProcess(1)
        }
    }

    @Synchronized
    private fun writeLog(error: Throwable, heading: String): File? = runCatching {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val file = File(DesktopPaths.logsDir, "crash-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))}.log")
        file.appendText(
            "=== ${LocalDateTime.now()} $heading (Nuvio ${DesktopBuildConfig.VERSION_NAME}, " +
                "${System.getProperty("os.name")} ${System.getProperty("os.version")})\n$trace\n",
        )
        file
    }.getOrNull()
}
