package com.nuvio.app.features.updater

import com.nuvio.app.desktop.DesktopAutoUpdate
import com.nuvio.app.desktop.DesktopBuildConfig
import com.nuvio.app.desktop.configureDesktopUpdates
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopUpdaterTest {
    private lateinit var server: HttpServer
    private val files = mutableMapOf<String, ByteArray>()
    private var downloads = 0

    @BeforeTest
    fun setUp() {
        configureDesktopUpdates()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.use {
                val body = files[exchange.requestURI.path]
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    if (exchange.requestURI.path.endsWith(".msi")) downloads++
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
                }
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Test
    fun picksTheMsiFromTheForkRelease() {
        assertEquals(DesktopBuildConfig.UPDATE_REPO, AppUpdateSource.repository)
        val release = """
            {"tag_name":"v9.1.0","name":"Nuvio 9.1.0","body":"notes","draft":false,"prerelease":false,
             "assets":[
               {"name":"Nuvio-9.1.0-arm64-v8a.apk","browser_download_url":"https://x/a.apk","size":1},
               {"name":"Nuvio-9.1.0-windows-x64.msi.sha256","browser_download_url":"https://x/n.msi.sha256","size":2},
               {"name":"Nuvio-9.1.0-windows-x64.msi","browser_download_url":"https://x/n.msi","size":3}
             ]}
        """.trimIndent()
        val update = assertNotNull(AppUpdaterRepository.selectUpdate(release, UpdateChannel.STABLE, listOf("windows-x64")))
        assertEquals("Nuvio-9.1.0-windows-x64.msi", update.assetName)
        assertEquals("v9.1.0", update.tag)
    }

    @Test
    fun downloadsVerifiesAndReusesTheInstaller() = runBlocking {
        val installer = ByteArray(300_000) { (it % 251).toByte() }
        files["/Nuvio-9.1.0.msi"] = installer
        files["/Nuvio-9.1.0.msi.sha256"] = "${sha256(installer)}  Nuvio-9.1.0.msi\n".toByteArray()

        var lastProgress = 0L
        val path = AppUpdaterPlatform.downloadApk("$base/Nuvio-9.1.0.msi", "Nuvio-9.1.0.msi") { done, _ ->
            lastProgress = done
        }.getOrThrow()
        assertTrue(File(path).readBytes().contentEquals(installer))
        assertEquals(installer.size.toLong(), lastProgress)
        assertEquals(1, downloads)

        // A later launch finds the same installer intact and skips the download.
        AppUpdaterPlatform.downloadApk("$base/Nuvio-9.1.0.msi", "Nuvio-9.1.0.msi") { _, _ -> }.getOrThrow()
        assertEquals(1, downloads)
    }

    @Test
    fun rejectsADamagedOrUncheckedInstaller() = runBlocking {
        files["/bad.msi"] = ByteArray(1000) { 1 }
        files["/bad.msi.sha256"] = "${"0".repeat(64)}  bad.msi".toByteArray()
        val damaged = AppUpdaterPlatform.downloadApk("$base/bad.msi", "bad.msi") { _, _ -> }
        assertTrue(damaged.exceptionOrNull()?.message.orEmpty().contains("checksum"), damaged.toString())

        files["/nosum.msi"] = ByteArray(10)
        val unchecked = AppUpdaterPlatform.downloadApk("$base/nosum.msi", "nosum.msi") { _, _ -> }
        assertTrue(unchecked.isFailure)
    }

    @Test
    fun autoUpdateIsOnByDefaultAndPersists() {
        assertTrue(AppUpdateSource.autoUpdate === DesktopAutoUpdate)
        val original = DesktopAutoUpdate.enabled.value
        assertTrue(original, "a fresh profile should have automatic updates on")
        try {
            DesktopAutoUpdate.setEnabled(false)
            assertEquals(false, DesktopAutoUpdate.enabled.value)
        } finally {
            DesktopAutoUpdate.setEnabled(original)
        }
    }

    @Test
    fun launchCheckDownloadsQuietlyThenAsksToInstallWhenAutoUpdateIsOn() = runBlocking {
        val installer = ByteArray(50_000) { 7 }
        files["/auto.msi"] = installer
        files["/auto.msi.sha256"] = sha256(installer).toByteArray()
        val update = AppUpdate(
            tag = "v999.0.0", title = "Nuvio 999", notes = "", releaseUrl = null,
            assetName = "auto.msi", assetUrl = "$base/auto.msi", assetSizeBytes = installer.size.toLong(),
        )
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        try {
            DesktopAutoUpdate.setEnabled(true)
            val auto = AppUpdaterController(scope, UpdatePreferences(), fetchUpdate = { Result.success(update) })
            auto.checkForUpdates(force = false, showNoUpdateFeedback = false)
            val ready = kotlinx.coroutines.withTimeout(10_000) {
                auto.uiState.first { it.downloadedApkPath != null }
            }
            assertTrue(ready.showDialog, "the banner should ask to install once the download is ready")
            assertEquals(1, downloads)

            DesktopAutoUpdate.setEnabled(false)
            val manual = AppUpdaterController(scope, UpdatePreferences(), fetchUpdate = { Result.success(update) })
            manual.checkForUpdates(force = false, showNoUpdateFeedback = false)
            val offered = kotlinx.coroutines.withTimeout(10_000) { manual.uiState.first { !it.isChecking && it.update != null } }
            assertTrue(offered.showDialog)
            assertEquals(null, offered.downloadedApkPath)
            assertEquals(false, offered.isDownloading)
            assertEquals(1, downloads, "with automatic updates off nothing downloads until the user asks")
        } finally {
            DesktopAutoUpdate.setEnabled(true)
            scope.cancel()
        }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
