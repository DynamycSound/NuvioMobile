package com.nuvio.app.features.downloads

import com.nuvio.app.desktop.DesktopPaths
import com.nuvio.app.desktop.compat.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads run as coroutines inside the app process (no OS job scheduler on Windows); files go
 * to `%USERPROFILE%\Videos\Nuvio`. A download interrupted by closing the app is restored as
 * Paused and resumes from its `.part` file with an HTTP Range request.
 */
internal actual object DownloadsPlatformDownloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val validators = ConcurrentHashMap<String, String>()

    private val directory: File get() = DesktopPaths.downloadsDir

    fun initialize(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val fileName = request.destinationFileName
        jobs.remove(fileName)?.cancel()
        val job = scope.launch {
            val destination = File(directory, fileName)
            var retries = 0
            while (true) {
                try {
                    DownloadSubtitles.prepare(request.item, destination.toURI().toString())
                    var lastProgressAt = 0L
                    val partial = if (destination.isFile) destination else transferDesktopDownload(
                        item = request.item,
                        directory = directory,
                        validator = validators[fileName],
                        onHeaders = { _, validator ->
                            if (validator != null) validators[fileName] = validator else validators.remove(fileName)
                        },
                        onProgress = { bytes, total ->
                            val now = System.currentTimeMillis()
                            if (now - lastProgressAt >= 1_000L || bytes == total) {
                                lastProgressAt = now
                                scope.launch(Dispatchers.Main) { onProgress(bytes, total) }
                            }
                        },
                    )
                    if (partial != destination && !partial.renameTo(destination)) {
                        throw IOException("Could not finalize the downloaded file")
                    }
                    validators.remove(fileName)
                    val bytes = destination.length()
                    withContext(Dispatchers.Main) { onSuccess(destination.toURI().toString(), bytes) }
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (shouldRetryDesktopDownload(error, retries)) {
                        delay(30_000L shl retries)
                        retries++
                    } else {
                        withContext(Dispatchers.Main) { onFailure(error.message ?: "Download failed") }
                        break
                    }
                }
            }
        }
        jobs[fileName] = job
        job.invokeOnCompletion { jobs.remove(fileName, job) }
        return object : DownloadsTaskHandle {
            override fun cancel() {
                job.cancel()
            }
        }
    }

    actual fun restoreItem(item: DownloadItem): DownloadItem =
        if (item.status == DownloadStatus.Downloading && !jobs.containsKey(item.fileName)) {
            item.copy(status = DownloadStatus.Paused, errorMessage = null)
        } else {
            item
        }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        jobs.remove(destinationFileName)?.cancel()
        validators.remove(destinationFileName)
        val partial = File(directory, "$destinationFileName.part")
        DownloadSubtitleStorage(File(directory, destinationFileName).toURI().toString()).remove()
        return !partial.exists() || partial.delete()
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri?.toLocalFileOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: return null
        return File(directory, fileName).takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean = runCatching {
        Desktop.getDesktop().open(directory.apply { mkdirs() })
        true
    }.getOrDefault(false)
}

private fun String.toLocalFileOrNull(): File? = runCatching {
    if (startsWith("file:")) File(URI(this)) else File(this)
}.getOrNull()
