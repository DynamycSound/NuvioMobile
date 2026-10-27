package com.nuvio.app.features.updater

import com.nuvio.app.desktop.DesktopPaths
import com.nuvio.app.desktop.DesktopTray
import com.nuvio.app.desktop.compat.DesktopPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Windows updates: the fork's releases carry an MSI and its `.sha256`. The MSI is downloaded,
 * checked, and installed by a small PowerShell step that waits for the app to exit, runs
 * msiexec (a per-user install, so no admin prompt) and starts the new version.
 */
actual object AppUpdaterPlatform {
    private val prefs by lazy { DesktopPreferences.open("app_updater") }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Set by the jpackage launcher; absent under `gradlew run`, which has no installed copy to replace. */
    private val launcherPath: String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }

    actual val isSupported: Boolean = true
    actual val isDebugBuild: Boolean = launcherPath == null

    actual fun getSupportedAbis(): List<String> = listOf("windows-x64")

    actual fun getIgnoredTag(): String? = prefs.getString(KEY_IGNORED_TAG, null)

    actual fun setIgnoredTag(tag: String?) {
        prefs.edit().apply { if (tag == null) remove(KEY_IGNORED_TAG) else putString(KEY_IGNORED_TAG, tag) }.apply()
    }

    actual fun getUpdateChannel(): String? = prefs.getString(KEY_CHANNEL, null)

    actual fun setUpdateChannel(channel: String) {
        prefs.edit().putString(KEY_CHANNEL, channel).apply()
    }

    actual fun deleteDownloadedApk(path: String) {
        File(path).delete()
    }

    actual suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(DesktopPaths.cacheDir, "updates").also { it.mkdirs() }
            val target = File(dir, assetName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            val expected = fetchChecksum(assetUrl)
            // An installer downloaded on an earlier launch is reused when it is intact.
            if (target.isFile && sha256(target) == expected) {
                onProgress(target.length(), target.length())
                return@runCatching target.absolutePath
            }
            dir.listFiles()?.filter { it.name.endsWith(".msi") || it.name.endsWith(".part") }?.forEach(File::delete)

            val partial = File(dir, target.name + ".part")
            client.newCall(Request.Builder().url(assetUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = response.body ?: error("Download failed: empty response")
                val total = body.contentLength().takeIf { it > 0 }
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            val actual = sha256(partial)
            if (actual != expected) {
                partial.delete()
                error("The downloaded installer is damaged (checksum mismatch). Please try again.")
            }
            target.delete()
            check(partial.renameTo(target)) { "Could not save the installer to ${target.path}" }
            target.absolutePath
        }
    }

    actual fun canRequestPackageInstalls(): Boolean = true

    actual fun openUnknownSourcesSettings() = Unit

    actual fun installDownloadedApk(path: String): Result<Unit> = runCatching {
        val launcher = launcherPath ?: error("Updates can only be installed from the installed Nuvio app.")
        val msi = File(path)
        check(msi.isFile) { "The downloaded installer is missing." }
        val log = File(msi.parentFile, "install.log")
        fun quote(value: String) = "'" + value.replace("'", "''") + "'"
        val script = """
            Wait-Process -Id ${ProcessHandle.current().pid()} -ErrorAction SilentlyContinue
            Start-Process -FilePath msiexec.exe -Wait -ArgumentList @('/i', ${quote("\"${msi.absolutePath}\"")}, '/passive', '/norestart', '/l*v', ${quote("\"${log.absolutePath}\"")})
            Start-Process -FilePath ${quote(launcher)}
        """.trimIndent()
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // The installer cannot replace files while the app runs, so the app closes now.
        DesktopPreferences.flushAll()
        DesktopTray.remove()
        exitProcess(0)
    }

    private fun fetchChecksum(assetUrl: String): String {
        client.newCall(Request.Builder().url("$assetUrl.sha256").build()).execute().use { response ->
            check(response.isSuccessful) { "This release has no installer checksum (HTTP ${response.code})." }
            val text = response.body?.string().orEmpty().trim()
            return text.substringBefore(' ').lowercase().takeIf { Regex("[0-9a-f]{64}").matches(it) }
                ?: error("This release's installer checksum is unreadable.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val KEY_IGNORED_TAG = "ignored_tag"
    private const val KEY_CHANNEL = "update_channel"
}
