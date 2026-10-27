package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.desktop.compat.Context
import com.nuvio.app.features.streams.redactedForLogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI

/**
 * Downloads subtitle files from remote URLs to the local cache and hands back file:// URIs,
 * so external players can read them (desktop port of the Android FileProvider version).
 */
object SubtitleFileCache {
    private const val SUBTITLE_CACHE_DIR = "subtitles"
    private val log = Logger.withTag("SubtitleFileCache")

    private var appContext: Context? = null
    private val okHttpClient: OkHttpClient by lazy { OkHttpClient() }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private val cacheDir: File?
        get() = appContext?.let { File(it.cacheDir, SUBTITLE_CACHE_DIR).also { dir -> dir.mkdirs() } }

    /**
     * Downloads subtitle files and returns updated [SubtitleInput] list with file:// URIs instead
     * of HTTP URLs. Subtitles that fail to download are skipped; returns null if none succeeded.
     */
    suspend fun cacheSubtitles(subtitles: List<SubtitleInput>): List<SubtitleInput>? {
        if (subtitles.isEmpty()) return null
        if (appContext == null) return null

        clearCache()

        val cached = subtitles.mapNotNull { input ->
            try {
                val localFile = downloadToCache(input)
                if (localFile != null) {
                    input.copy(url = localFile.toURI().toString())
                } else {
                    log.w { "Failed to download subtitle: ${input.name}" }
                    null
                }
            } catch (e: Exception) {
                log.w(e) { "Error caching subtitle: ${input.name}" }
                null
            }
        }

        return cached.ifEmpty { null }
    }

    private suspend fun downloadToCache(input: SubtitleInput): File? =
        withContext(Dispatchers.IO) {
            val dir = cacheDir ?: return@withContext null
            val extension = guessExtension(input.url)
            val filename = sanitizeFilename("${input.lang}_${input.name}.$extension")
            val file = File(dir, filename)

            try {
                if (input.url.startsWith("file:")) {
                    File(URI(input.url)).copyTo(file, overwrite = true)
                    return@withContext file
                }
                val request = Request.Builder().url(input.url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.w { "HTTP ${response.code} downloading subtitle: ${input.url.redactedForLogs()}" }
                        return@withContext null
                    }

                    val body = response.body ?: return@withContext null
                    file.outputStream().use { output ->
                        body.byteStream().copyTo(output)
                    }
                }
                file
            } catch (e: Exception) {
                log.w(e) { "Failed to download subtitle file: ${input.url.redactedForLogs()}" }
                file.delete()
                null
            }
        }

    /** Removes all cached subtitle files. */
    fun clearCache() {
        try {
            cacheDir?.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            log.w(e) { "Failed to clear subtitle cache" }
        }
    }

    private fun guessExtension(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        return when {
            path.endsWith(".vtt", ignoreCase = true) -> "vtt"
            path.endsWith(".ass", ignoreCase = true) -> "ass"
            path.endsWith(".ssa", ignoreCase = true) -> "ssa"
            path.endsWith(".ttml", ignoreCase = true) -> "ttml"
            path.endsWith(".dfxp", ignoreCase = true) -> "dfxp"
            else -> "srt"
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            .take(100)
    }
}
