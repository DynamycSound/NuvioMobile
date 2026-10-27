package com.nuvio.app.features.notifications

import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.desktop.DesktopTray
import com.nuvio.app.desktop.DesktopWindowState
import com.nuvio.app.desktop.compat.Context
import com.nuvio.app.desktop.compat.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Windows has no OS-level scheduler for app notifications without an installed service, so
 * scheduled episode reminders are kept in a preference and shown as tray notifications by a
 * ticker while Nuvio runs. Reminders that came due while the app was closed are shown at the
 * next launch (once), like a missed-notification summary.
 */
internal actual object EpisodeReleaseNotificationPlatform {
    private const val preferencesName = "nuvio_episode_release_desktop"
    private const val scheduledKey = "scheduled_requests"
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        scope.launch {
            while (true) {
                fireDue()
                delay(60_000)
            }
        }
    }

    actual suspend fun notificationsAuthorized(): Boolean = DesktopTray.isSupported

    actual suspend fun requestAuthorization(): Boolean = DesktopTray.isSupported

    actual suspend fun scheduleEpisodeReleaseNotifications(requests: List<EpisodeReleaseNotificationRequest>) {
        val now = LocalDateTime.now()
        val pending = requests.mapNotNull { request ->
            val fireAt = fireTime(request.releaseDateIso) ?: return@mapNotNull null
            if (!fireAt.isAfter(now)) return@mapNotNull null
            Scheduled(
                id = request.requestId,
                title = request.notificationTitle,
                body = request.notificationBody,
                deepLinkUrl = request.deepLinkUrl,
                fireAtEpochMs = fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            )
        }
        save(pending)
    }

    actual suspend fun clearScheduledEpisodeReleaseNotifications() {
        save(emptyList())
    }

    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) {
        show(request.notificationTitle, request.notificationBody, request.deepLinkUrl)
    }

    private fun fireDue() {
        val all = load()
        if (all.isEmpty()) return
        val now = System.currentTimeMillis()
        val (due, later) = all.partition { it.fireAtEpochMs <= now }
        if (due.isEmpty()) return
        save(later)
        due.forEach { show(it.title, it.body, it.deepLinkUrl) }
    }

    private fun show(title: String, body: String, deepLinkUrl: String) {
        DesktopTray.notify(title, body) {
            DesktopWindowState.bringToFront()
            if (deepLinkUrl.isNotBlank()) handleAppUrl(deepLinkUrl)
        }
    }

    private fun fireTime(releaseDateIso: String): LocalDateTime? = runCatching {
        LocalDate.parse(releaseDateIso.take(10))
            .atTime(LocalTime.of(EpisodeReleaseNotificationHour, EpisodeReleaseNotificationMinute))
    }.getOrNull()

    @Synchronized
    private fun load(): List<Scheduled> {
        val raw = preferences?.getString(scheduledKey, null) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Scheduled.serializer()), raw) }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(items: List<Scheduled>) {
        preferences?.edit()
            ?.putString(scheduledKey, json.encodeToString(ListSerializer(Scheduled.serializer()), items))
            ?.apply()
    }

    @Serializable
    private data class Scheduled(
        val id: String,
        val title: String,
        val body: String,
        val deepLinkUrl: String,
        val fireAtEpochMs: Long,
    )
}
