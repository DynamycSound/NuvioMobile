package com.nuvio.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.nuvio.app.App
import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.core.network.ServerConfigurationStorage
import com.nuvio.app.core.poster.CustomPosterUrlStorage
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.core.sync.SyncClientIdentityStorage
import com.nuvio.app.core.ui.CardDepthStyleStorage
import com.nuvio.app.core.ui.PosterCardStyleStorage
import com.nuvio.app.desktop.compat.DesktopContext
import com.nuvio.app.desktop.compat.DesktopPreferences
import com.nuvio.app.features.addons.AddonHttpClientProvider
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.collection.CollectionMobileSettingsStorage
import com.nuvio.app.features.collection.CollectionStorage
import com.nuvio.app.features.debrid.DebridSettingsStorage
import com.nuvio.app.features.details.MetaScreenSettingsStorage
import com.nuvio.app.features.details.SeasonViewModeStorage
import com.nuvio.app.features.downloads.DownloadsPlatformDownloader
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.home.HomeCatalogSettingsStorage
import com.nuvio.app.features.library.LibraryDisplaySettingsStorage
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.mdblist.MdbListSettingsStorage
import com.nuvio.app.features.mdblist.PlatformMdbListAuthPersistence
import com.nuvio.app.features.mdblist.PlatformMdbListSyncStorage
import com.nuvio.app.features.membership.MemberAssetStorage
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsStorage
import com.nuvio.app.features.p2p.P2pSettingsStorage
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.PlayerTrackPreferenceStorage
import com.nuvio.app.features.player.SubtitleFileCache
import com.nuvio.app.features.plugins.PluginStorage
import com.nuvio.app.features.profiles.AvatarStorage
import com.nuvio.app.features.profiles.ProfilePinCacheStorage
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.app.features.search.DiscoverSelectionStorage
import com.nuvio.app.features.search.SearchHistoryStorage
import com.nuvio.app.features.servers.ServerStorage
import com.nuvio.app.features.settings.SentrySettingsStorage
import com.nuvio.app.features.settings.ThemeSettingsStorage
import com.nuvio.app.features.shuffle.EpisodeShuffleStorage
import com.nuvio.app.features.simkl.SimklAuthStorage
import com.nuvio.app.features.simkl.SimklSyncStorage
import com.nuvio.app.features.streams.BingeGroupCacheStorage
import com.nuvio.app.features.streams.StreamBadgeSettingsStorage
import com.nuvio.app.features.streams.StreamLinkCacheStorage
import com.nuvio.app.features.tmdb.TmdbSettingsStorage
import com.nuvio.app.features.trakt.TraktAuthStorage
import com.nuvio.app.features.trakt.TraktCommentsStorage
import com.nuvio.app.features.trakt.TraktLibraryStorage
import com.nuvio.app.features.trakt.TraktSettingsStorage
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesStorage
import com.nuvio.app.features.watchprogress.ResumePromptStorage
import com.nuvio.app.features.watchprogress.WatchProgressStorage
import org.jetbrains.skia.Image
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/** Same storage set and order as Android's MainActivity.onCreate. */
internal fun initializePlatformStorage() {
    val context = DesktopContext
    ThemeSettingsStorage.initialize(context)
    SentrySettingsStorage.initialize(context)
    SyncClientIdentityStorage.initialize(context)
    AddonHttpClientProvider.initialize(context)
    AddonStorage.initialize(context)
    AuthStorage.initialize(context)
    ServerConfigurationStorage.initialize(context)
    LibraryStorage.initialize(context)
    WatchedStorage.initialize(context)
    MetaScreenSettingsStorage.initialize(context)
    EpisodeShuffleStorage.initialize(context)
    HomeCatalogSettingsStorage.initialize(context)
    PlayerSettingsStorage.initialize(context)
    PlayerTrackPreferenceStorage.initialize(context)
    P2pSettingsStorage.initialize(context)
    ExternalPlayerPlatform.initialize(context)
    SubtitleFileCache.initialize(context)
    ProfileStorage.initialize(context)
    AvatarStorage.initialize(context)
    ProfilePinCacheStorage.initialize(context)
    MemberAssetStorage.initialize(context)
    DiscoverSelectionStorage.initialize(context)
    SearchHistoryStorage.initialize(context)
    SeasonViewModeStorage.initialize(context)
    PosterCardStyleStorage.initialize(context)
    CustomPosterUrlStorage.initialize(context)
    CardDepthStyleStorage.initialize(context)
    DebridSettingsStorage.initialize(context)
    TmdbSettingsStorage.initialize(context)
    MdbListSettingsStorage.initialize(context)
    TraktAuthStorage.initialize(context)
    TraktCommentsStorage.initialize(context)
    TraktLibraryStorage.initialize(context)
    TraktSettingsStorage.initialize(context)
    PlatformMdbListAuthPersistence.initialize(context)
    ServerStorage.initialize(context)
    PlatformMdbListSyncStorage.initialize(context)
    SimklAuthStorage.initialize(context)
    SimklSyncStorage.initialize(context)
    LibraryDisplaySettingsStorage.initialize(context)
    ContinueWatchingPreferencesStorage.initialize(context)
    ResumePromptStorage.initialize(context)
    ContinueWatchingEnrichmentStorage.initialize(context)
    EpisodeReleaseNotificationsStorage.initialize(context)
    WatchProgressStorage.initialize(context)
    StreamLinkCacheStorage.initialize(context)
    StreamBadgeSettingsStorage.initialize(context)
    BingeGroupCacheStorage.initialize(context)
    PluginStorage.initialize(context)
    CollectionMobileSettingsStorage.initialize(context)
    CollectionStorage.initialize(context)
    DownloadsStorage.initialize(context)
    DownloadsPlatformDownloader.initialize(context)
    PlatformLocalAccountDataCleaner.initialize(context)
    EpisodeReleaseNotificationPlatform.initialize(context)
}

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    DesktopCrashHandler.installThreadHandler()
    val launchUrl = args.firstOrNull { it.contains("://") }
    val onlyInstance = DesktopSingleInstance.claim(launchUrl) { url ->
        EventQueue.invokeLater {
            url?.let(::handleAppUrl)
            DesktopWindowState.bringToFront()
        }
    }
    if (!onlyInstance) return
    initializePlatformStorage()
    configureDesktopUpdates()
    launchUrl?.let(::handleAppUrl)

    application {
        val windowState = rememberWindowState(
            size = DpSize(1280.dp, 800.dp),
            position = WindowPosition.PlatformDefault,
        )
        val icon = remember {
            val bytes = DesktopTray::class.java.getResourceAsStream("/icons/nuvio.png")!!.use { it.readAllBytes() }
            BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
        }
        var escapeLeftFullscreen by remember { mutableStateOf(false) }

        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides DesktopCrashHandler) {
            Window(
                onCloseRequest = {
                    DesktopPreferences.flushAll()
                    DesktopTray.remove()
                    exitApplication()
                },
                state = windowState,
                title = "Nuvio",
                icon = icon,
                onPreviewKeyEvent = { event ->
                    val keyDown = event.type == KeyEventType.KeyDown
                    when {
                        keyDown && event.key == Key.F11 -> {
                            DesktopWindowState.toggleFullscreen()
                            true
                        }
                        // In fullscreen Esc leaves fullscreen, like other players; otherwise it goes back.
                        // Its release is swallowed too, so the back gesture does not see half a key press.
                        keyDown && event.key == Key.Escape && DesktopWindowState.fullscreen.value -> {
                            DesktopWindowState.setFullscreen(false)
                            escapeLeftFullscreen = true
                            true
                        }
                        event.key == Key.Escape && event.type == KeyEventType.KeyUp && escapeLeftFullscreen -> {
                            escapeLeftFullscreen = false
                            true
                        }
                        else -> DesktopPlayerKeys.handle(event)
                    }
                },
            ) {
                DisposableEffect(window) {
                    window.minimumSize = Dimension(900, 600)
                    DesktopWindowState.window = window
                    val listener = object : WindowAdapter() {
                        override fun windowIconified(e: WindowEvent) = DesktopWindowState.setVisible(false)
                        override fun windowDeiconified(e: WindowEvent) = DesktopWindowState.setVisible(true)
                    }
                    window.addWindowListener(listener)
                    onDispose {
                        window.removeWindowListener(listener)
                        DesktopWindowState.window = null
                    }
                }
                LaunchedEffect(Unit) {
                    // Runs on the AWT event thread, as DesktopFullscreen needs.
                    DesktopWindowState.fullscreen.collect { fullscreen -> DesktopFullscreen.set(window, fullscreen) }
                }

                // Esc is already a back gesture in Compose desktop; the mouse "back" button is added here.
                val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
                val mouseBack = remember { DirectNavigationEventInput() }
                DisposableEffect(dispatcher) {
                    dispatcher?.addInput(mouseBack)
                    onDispose { dispatcher?.removeInput(mouseBack) }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                            if (event.button == PointerButton.Back) mouseBack.backCompleted()
                        },
                ) {
                    App()
                }
            }
        }
    }
}
