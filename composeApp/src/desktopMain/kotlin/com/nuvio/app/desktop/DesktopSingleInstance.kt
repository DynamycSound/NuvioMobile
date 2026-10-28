package com.nuvio.app.desktop

import co.touchlab.kermit.Logger
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.SecureRandom
import java.util.HexFormat
import javax.swing.JOptionPane
import kotlin.concurrent.thread

/**
 * One Nuvio per data folder: two processes would write the same preferences over each other, and
 * a second window is never what a second click on the shortcut means.
 *
 * The first process locks a file in the data folder and listens on a loopback port, written next to
 * the lock with a random token. A later launch sends the token and its link (if any) there and exits;
 * the first one opens the link and comes to the front.
 */
internal object DesktopSingleInstance {
    private const val TAG = "NuvioDesktopInstance"
    private const val CONNECT_ATTEMPTS = 25
    private const val CONNECT_RETRY_MS = 200L

    // Held for the life of the process; the OS drops the lock when it exits, even after a crash.
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /**
     * True when this process is the only one, and [onActivate] then runs on a background thread for
     * each later launch. False when another one runs: [url] went to it, or it did not answer and
     * the person was told; this process should exit either way.
     */
    fun claim(url: String?, onActivate: (url: String?) -> Unit): Boolean {
        val dir = DesktopPaths.dataDir.also { it.mkdirs() }
        val portFile = File(dir, "instance.port")
        val channel = RandomAccessFile(File(dir, "instance.lock"), "rw").channel
        val acquired = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (acquired == null) {
            channel.close()
            if (!handOver(portFile, url)) {
                JOptionPane.showMessageDialog(
                    null,
                    "Nuvio is already running but does not respond.\nClose it in Task Manager, then start Nuvio again.",
                    "Nuvio",
                    JOptionPane.WARNING_MESSAGE,
                )
            }
            return false
        }
        lock = acquired
        listen(portFile, onActivate)
        return true
    }

    private fun listen(portFile: File, onActivate: (url: String?) -> Unit) {
        val token = HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes))
        val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = socket
        portFile.writeText("${socket.localPort} $token")
        thread(name = "nuvio-instance", isDaemon = true) {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (error: IOException) {
                    if (socket.isClosed) break
                    Logger.w(TAG, error) { "Could not accept a later launch" }
                    continue
                }
                client.use {
                    it.soTimeout = 2_000
                    val message = runCatching { it.getInputStream().bufferedReader(Charsets.UTF_8).readLine() }
                        .onFailure { error -> Logger.w(TAG, error) { "Could not read a later launch" } }
                        .getOrNull()
                    if (message == null || !message.startsWith("$token\t")) {
                        Logger.w(TAG) { "Ignored a connection without the instance token" }
                        return@use
                    }
                    onActivate(message.substringAfter('\t').takeIf { url -> url.isNotBlank() })
                }
            }
        }
    }

    /** False when the running process could not be reached, e.g. it is still starting or hung. */
    private fun handOver(portFile: File, url: String?): Boolean {
        repeat(CONNECT_ATTEMPTS) {
            val parts = runCatching { portFile.readText().trim().split(' ') }.getOrNull()
            val port = parts?.firstOrNull()?.toIntOrNull()
            val token = parts?.getOrNull(1)
            if (port != null && token != null) {
                val sent = runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
                        socket.getOutputStream().write("$token\t${url.orEmpty()}\n".toByteArray(Charsets.UTF_8))
                    }
                }
                if (sent.isSuccess) return true
            }
            Thread.sleep(CONNECT_RETRY_MS)
        }
        Logger.w(TAG) { "Another Nuvio holds the data folder but did not answer" }
        return false
    }
}
