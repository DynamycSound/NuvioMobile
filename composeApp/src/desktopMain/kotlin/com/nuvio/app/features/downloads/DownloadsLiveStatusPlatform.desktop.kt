package com.nuvio.app.features.downloads

import com.nuvio.app.desktop.DesktopTray
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader.openDownloadsDirectory

/**
 * Android shows a progress notification per download; on Windows a tray notification is shown
 * when a download finishes or fails (progress itself is visible on the Downloads screen).
 */
internal actual object DownloadsLiveStatusPlatform {
    private val lastStatus = HashMap<String, DownloadStatus>()

    @Synchronized
    actual fun onItemsChanged(items: List<DownloadItem>) {
        val first = lastStatus.isEmpty()
        items.forEach { item ->
            val previous = lastStatus.put(item.id, item.status)
            if (first || previous == null || previous == item.status) return@forEach
            when (item.status) {
                DownloadStatus.Completed -> DesktopTray.notify("Download complete", item.displayName()) {
                    openDownloadsDirectory()
                }
                DownloadStatus.Failed -> DesktopTray.notify(
                    "Download failed",
                    listOfNotNull(item.displayName(), item.errorMessage).joinToString("\n"),
                )
                else -> Unit
            }
        }
        lastStatus.keys.retainAll(items.map { it.id }.toSet())
    }

    private fun DownloadItem.displayName(): String =
        if (displaySubtitle.isBlank()) title else "$title · $displaySubtitle"
}
