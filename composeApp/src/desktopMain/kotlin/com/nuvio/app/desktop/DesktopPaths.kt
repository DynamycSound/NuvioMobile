package com.nuvio.app.desktop

import java.io.File

/**
 * Where the desktop app keeps its data.
 *
 * Settings and account data live in `%APPDATA%\Nuvio` (roams with the Windows profile and
 * survives app updates/reinstalls); caches go to `%LOCALAPPDATA%\Nuvio\cache`.
 * `-Dnuvio.dataDir=...` overrides both, which is handy for testing with a clean profile.
 */
object DesktopPaths {
    private val override: File? = System.getProperty("nuvio.dataDir")?.takeIf { it.isNotBlank() }?.let(::File)

    val dataDir: File by lazy {
        (override ?: File(env("APPDATA") ?: "${System.getProperty("user.home")}/AppData/Roaming", "Nuvio"))
            .also { it.mkdirs() }
    }

    val filesDir: File by lazy { File(dataDir, "files").also { it.mkdirs() } }

    val cacheDir: File by lazy {
        (override?.let { File(it, "cache") }
            ?: File(env("LOCALAPPDATA") ?: "${System.getProperty("user.home")}/AppData/Local", "Nuvio/cache"))
            .also { it.mkdirs() }
    }

    /** Crash reports; next to the cache in `%LOCALAPPDATA%\Nuvio`. */
    val logsDir: File by lazy {
        (override?.let { File(it, "logs") }
            ?: File(env("LOCALAPPDATA") ?: "${System.getProperty("user.home")}/AppData/Local", "Nuvio/logs"))
            .also { it.mkdirs() }
    }

    val downloadsDir: File by lazy {
        File(System.getProperty("user.home"), "Videos/Nuvio").also { it.mkdirs() }
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
}
