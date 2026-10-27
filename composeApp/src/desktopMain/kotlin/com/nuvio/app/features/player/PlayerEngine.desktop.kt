package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.nuvio.app.desktop.DesktopPlayerKeys
import com.nuvio.app.desktop.DesktopWindowState
import com.nuvio.app.features.player.mpv.MpvPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.player_error_mpv_unavailable
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.max
import kotlin.math.min

private const val KEYBOARD_SEEK_MS = 10_000L

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    playbackEngine: AndroidPlaybackEngine?,
    useNativeController: Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val latestOnControllerReady = rememberUpdatedState(onControllerReady)
    val latestOnSnapshot = rememberUpdatedState(onSnapshot)
    val latestOnError = rememberUpdatedState(onError)
    val latestOnInitialPositionHandled = rememberUpdatedState(onInitialPositionHandled)

    val player = remember { MpvPlayer.create() }
    if (player == null) {
        LaunchedEffect(Unit) { latestOnError.value(getString(Res.string.player_error_mpv_unavailable)) }
        Box(modifier.background(Color.Black))
        return
    }
    DisposableEffect(player) {
        onDispose { player.destroy() }
    }

    val controller = remember(player) { DesktopPlayerController(player) }
    DisposableEffect(controller) {
        DesktopPlayerKeys.active = controller
        onDispose { if (DesktopPlayerKeys.active === controller) DesktopPlayerKeys.active = null }
    }

    LaunchedEffect(controller, sourceUrl, sourceAudioUrl, sourceHeaders, sourceResponseHeaders) {
        latestOnControllerReady.value(controller)
    }

    LaunchedEffect(player, sourceUrl, sourceAudioUrl, sourceHeaders, externalSubtitles) {
        player.setPaused(!playWhenReady)
        // Starting at the resume point avoids a flash of the first seconds before the screen's seek.
        player.load(sourceUrl, sourceAudioUrl, sourceHeaders, externalSubtitles, initialPositionMs)
        if (initialPositionMs != null && initialPositionMs > 0L && initialPositionRequestKey != null) {
            latestOnInitialPositionHandled.value(initialPositionRequestKey, true)
        }
    }

    LaunchedEffect(player, playWhenReady) {
        player.setPaused(!playWhenReady)
    }

    LaunchedEffect(player, resizeMode) {
        player.cropToSurface = resizeMode == PlayerResizeMode.Zoom
        player.requestRender(force = true)
    }

    LaunchedEffect(player) {
        var lastReportedError: String? = null
        while (isActive) {
            latestOnSnapshot.value(withContext(Dispatchers.IO) { player.snapshot() })
            val errorMessage = player.errorMessage
            if (errorMessage != lastReportedError) {
                lastReportedError = errorMessage
                latestOnError.value(errorMessage)
            }
            delay(250L)
        }
    }

    // Skia copies a mutable bitmap when it becomes an Image, so the copy is made once per new frame.
    val imageCache = remember(player) { FrameImageCache() }
    DisposableEffect(imageCache) {
        onDispose { imageCache.clear() }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged { size ->
                player.surfaceWidth = size.width
                player.surfaceHeight = size.height
                player.requestRender(force = true)
            }
            .drawBehind {
                player.frameVersion.longValue
                val frame = player.takeFrame() ?: return@drawBehind
                val image = imageCache.imageFor(frame)
                val frameWidth = frame.width.toFloat()
                val frameHeight = frame.height.toFloat()
                val destination = when (resizeMode) {
                    PlayerResizeMode.Fill -> Rect.makeWH(size.width, size.height)
                    PlayerResizeMode.Fit, PlayerResizeMode.Zoom -> {
                        val scale = if (resizeMode == PlayerResizeMode.Fit) {
                            min(size.width / frameWidth, size.height / frameHeight)
                        } else {
                            max(size.width / frameWidth, size.height / frameHeight)
                        }
                        val width = frameWidth * scale
                        val height = frameHeight * scale
                        Rect.makeXYWH((size.width - width) / 2f, (size.height - height) / 2f, width, height)
                    }
                }
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawImageRect(
                        image,
                        Rect.makeWH(frameWidth, frameHeight),
                        destination,
                        SamplingMode.MITCHELL,
                        null,
                        true,
                    )
                }
            },
    ) {
        if (useNativeController) {
            var isPlayingLocal by remember { mutableStateOf(playWhenReady) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (!isPlayingLocal) Color.Black.copy(alpha = 0.4f) else Color.Transparent)
                    .clickable {
                        isPlayingLocal = !isPlayingLocal
                        player.setPaused(!isPlayingLocal)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (!isPlayingLocal) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(Res.string.action_play),
                        tint = Color.White,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
        }
    }
}

/** UI thread only. */
private class FrameImageCache {
    private var frame: MpvPlayer.Frame? = null
    private var version = -1L
    private var image: Image? = null

    fun imageFor(next: MpvPlayer.Frame): Image {
        val nextVersion = next.bitmap.generationId.toLong()
        val cached = image
        if (cached != null && frame === next && version == nextVersion) return cached
        cached?.close()
        return Image.makeFromBitmap(next.bitmap).also {
            image = it
            frame = next
            version = nextVersion
        }
    }

    fun clear() {
        image?.close()
        image = null
        frame = null
    }
}

private class DesktopPlayerController(
    private val player: MpvPlayer,
) : PlayerEngineController, PlayerSystemControlSource, DesktopPlayerKeys.Handler {
    @Volatile private var systemControlHandler: PlayerSystemControlHandler? = null

    // A minimised window keeps playing on desktop, unlike a backgrounded phone app.
    override val keepsPlaybackInBackground: Boolean = true

    override fun setSystemControlHandler(handler: PlayerSystemControlHandler?) {
        systemControlHandler = handler
    }

    override fun play() = player.setPaused(false)

    override fun pause() = player.setPaused(true)

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun seekBy(offsetMs: Long) = player.seekBy(offsetMs)

    override fun retry() = player.retry()

    override fun setPlaybackSpeed(speed: Float) = player.setSpeed(speed)

    override fun setMuted(muted: Boolean) = player.setMuted(muted)

    override fun getAudioTracks(): List<AudioTrack> =
        player.tracks().filter { it.type == "audio" }.map { track ->
            AudioTrack(
                index = track.index,
                id = track.id.toString(),
                label = track.label,
                language = track.language.ifBlank { null },
                isSelected = track.isSelected,
            )
        }

    override fun getSubtitleTracks(): List<SubtitleTrack> =
        player.tracks().filter { it.type == "sub" }.map { track ->
            SubtitleTrack(
                index = track.index,
                id = track.id.toString(),
                label = track.label,
                language = track.language.ifBlank { null },
                isSelected = track.isSelected,
                isForced = inferForcedSubtitleTrack(
                    label = track.label,
                    language = track.language,
                    trackId = track.id.toString(),
                ),
            )
        }

    override fun applyAudioLanguagePreferences(languages: List<String>) = player.applyAudioLanguages(languages)

    override fun selectAudioTrack(index: Int) {
        trackId("audio", index)?.let(player::selectAudio)
    }

    override fun selectSubtitleTrack(index: Int) {
        if (index < 0) player.selectSubtitle(-1) else trackId("sub", index)?.let(player::selectSubtitle)
    }

    override fun setSubtitleUri(url: String) = player.addSubtitleUrl(url)

    override fun clearExternalSubtitle() = player.removeExternalSubtitles(selectTrackId = -1)

    override fun clearExternalSubtitleAndSelect(trackIndex: Int) {
        // Resolved before the external tracks are removed, while the index still points at the right track.
        val trackId = if (trackIndex < 0) -1 else trackId("sub", trackIndex) ?: -1
        player.removeExternalSubtitles(selectTrackId = trackId)
    }

    override fun setSubtitleDelayMs(delayMs: Int) =
        player.setSubtitleDelayMs(delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS))

    override fun applySubtitleStyle(style: SubtitleStyleState) = player.applySubtitleStyle(
        textColor = style.textColor.toMpvColorString(),
        backgroundColor = style.backgroundColor.toMpvColorString(),
        outlineColor = style.outlineColor.toMpvColorString(),
        outlineSize = if (style.outlineEnabled) style.outlineWidth.toFloat() else 0f,
        bold = style.bold,
        // Same scale as iOS, which also renders subtitles with mpv/libass.
        fontSize = (style.fontSizeSp * 3f).coerceIn(18f, 96f),
        subPos = (100 - (style.bottomOffset / 2)).coerceIn(0, 150),
        stripSdh = style.stripSdh,
    )

    override suspend fun getMediaInfo(): PlayerMediaInfo = withContext(Dispatchers.IO) {
        mpvMediaInfo { name -> player.getString(name)?.ifBlank { null } }
    }

    private fun trackId(type: String, index: Int): Int? =
        player.tracks().firstOrNull { it.type == type && it.index == index }?.id

    override fun onKey(event: KeyEvent): Boolean {
        when (event.key) {
            Key.Spacebar, Key.K, Key.MediaPlayPause -> {
                val playing = !player.isPaused()
                if (systemControlHandler?.handleSetPlaying(!playing) != true) player.setPaused(playing)
            }
            Key.DirectionLeft, Key.J -> seekWithKeyboard(-KEYBOARD_SEEK_MS)
            Key.DirectionRight, Key.L -> seekWithKeyboard(KEYBOARD_SEEK_MS)
            Key.F -> DesktopWindowState.toggleFullscreen()
            else -> return false
        }
        return true
    }

    private fun seekWithKeyboard(offsetMs: Long) {
        if (systemControlHandler?.handleSeekBy(offsetMs) != true) player.seekBy(offsetMs)
    }
}

private fun Color.toMpvColorString(): String {
    fun Float.hex() = (this * 255f).toInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
    return "#" + alpha.hex() + red.hex() + green.hex() + blue.hex()
}
