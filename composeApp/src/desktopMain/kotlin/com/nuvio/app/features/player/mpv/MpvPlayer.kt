package com.nuvio.app.features.player.mpv

import androidx.compose.runtime.mutableLongStateOf
import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.PlayerChapter
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.streams.StreamSubtitle
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.swing.SwingUtilities
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "NuvioDesktopPlayer"

/**
 * One libmpv instance with a software render context.
 *
 * mpv decodes (hardware decoding with copy-back) and renders each frame, subtitles included,
 * straight into the pixels of a Skia bitmap; Compose then draws that bitmap like any image, so
 * the shared player controls can sit on top of the video. A native child window would be faster
 * but always paints over Compose content, which would hide the controls.
 *
 * Threads: a render thread (woken by mpv's update callback), an event thread (mpv_wait_event),
 * and a loader thread for commands that may block on the network (loadfile, sub-add).
 * Every libmpv call goes through [api], which refuses calls once [destroy] has started.
 */
internal class MpvPlayer private constructor(
    private val lib: LibMpv,
    private val handle: Pointer,
    private val renderContext: Pointer,
) {
    data class Track(
        val index: Int,
        val id: Int,
        val type: String,
        val label: String,
        val language: String,
        val isSelected: Boolean,
        val isExternal: Boolean,
    )

    /** A video frame in a Skia bitmap. Frames move between the render and UI threads, never shared. */
    class Frame(val bitmap: Bitmap, val width: Int, val height: Int, val address: Long, val rowBytes: Int)

    private val apiLock = ReentrantReadWriteLock()
    @Volatile private var alive = true

    // Written by the UI, read by the render thread.
    @Volatile var surfaceWidth = 0
    @Volatile var surfaceHeight = 0
    @Volatile var cropToSurface = false

    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0

    /** Bumped on the UI thread after each new frame, so the surface redraws. */
    val frameVersion = mutableLongStateOf(0L)
    private val freeFrames = ConcurrentLinkedQueue<Frame>()
    private val pendingFrame = AtomicReference<Frame?>(null)
    private var shownFrame: Frame? = null

    private val renderSignal = Object()
    private var renderRequested = false
    private var forceRender = false
    private val updateCallback = LibMpv.UpdateCallback { requestRender(force = false) }

    private val swSize = Memory(8)
    private val swFormat = Memory(5).apply { setString(0, "rgb0", "US-ASCII") }
    private val swStride = Memory(8)
    private val renderParams = Memory(16L * 5).apply {
        clear()
        setInt(0, LibMpv.RENDER_PARAM_SW_SIZE); setPointer(8, swSize)
        setInt(16, LibMpv.RENDER_PARAM_SW_FORMAT); setPointer(24, swFormat)
        setInt(32, LibMpv.RENDER_PARAM_SW_STRIDE); setPointer(40, swStride)
        setInt(48, LibMpv.RENDER_PARAM_SW_POINTER)
        setInt(64, LibMpv.RENDER_PARAM_INVALID)
    }

    private val loader = Executors.newSingleThreadExecutor { Thread(it, "mpv-loader").apply { isDaemon = true } }
    private lateinit var renderThread: Thread
    private lateinit var eventThread: Thread

    private val logLock = Any()
    private val recentLogs = ArrayDeque<String>()
    @Volatile var errorMessage: String? = null
        private set
    @Volatile private var loading = true

    // Extra sources mpv can only attach once the main file is loaded. Loader thread only.
    private var pendingAudioUrl: String? = null
    private var pendingSubtitles: List<StreamSubtitle> = emptyList()
    private var activeHeaders: Map<String, String> = emptyMap()
    private var defaultUserAgent: String = ""

    private fun start() {
        defaultUserAgent = getString("user-agent").orEmpty()
        lib.mpv_render_context_set_update_callback(renderContext, updateCallback, null)
        renderThread = thread(name = "mpv-render", isDaemon = true) { renderLoop() }
        eventThread = thread(name = "mpv-events", isDaemon = true) { eventLoop() }
    }

    // region Rendering

    fun requestRender(force: Boolean) {
        synchronized(renderSignal) {
            renderRequested = true
            if (force) forceRender = true
            renderSignal.notifyAll()
        }
    }

    private fun renderLoop() {
        while (alive) {
            val force: Boolean
            synchronized(renderSignal) {
                while (alive && !renderRequested) renderSignal.wait(250)
                force = forceRender
                renderRequested = false
                forceRender = false
            }
            if (!alive) break
            val flags = api(0L) { lib.mpv_render_context_update(renderContext) }
            // mpv redraws the current frame on demand, which is how a resize repaints while paused.
            if (force || flags and LibMpv.RENDER_UPDATE_FRAME != 0L) renderFrame()
        }
    }

    private fun renderFrame() {
        val (width, height) = targetSize() ?: return
        val frame = obtainFrame(width, height)
        swSize.setInt(0, width)
        swSize.setInt(4, height)
        swStride.setLong(0, frame.rowBytes.toLong())
        renderParams.setPointer(56, Pointer(frame.address))
        val status = api(-1) { lib.mpv_render_context_render(renderContext, renderParams) }
        if (status < 0) {
            freeFrames.add(frame)
            return
        }
        frame.bitmap.notifyPixelsChanged()
        pendingFrame.getAndSet(frame)?.let(freeFrames::add)
        SwingUtilities.invokeLater { frameVersion.longValue++ }
    }

    /**
     * Video-shaped and never above the video's own size: upscaling on the CPU is slow, and Skia
     * scales the frame on the GPU when it is drawn. Crop modes cover the surface instead of fitting it.
     */
    private fun targetSize(): Pair<Int, Int>? {
        val vw = videoWidth
        val vh = videoHeight
        // Before the first VIDEO_RECONFIG (and for audio-only files) there is no picture to draw.
        if (vw <= 0 || vh <= 0) return null
        val sw = surfaceWidth
        val sh = surfaceHeight
        if (sw <= 0 || sh <= 0) return vw to vh
        val fit = min(sw.toDouble() / vw, sh.toDouble() / vh)
        val cover = max(sw.toDouble() / vw, sh.toDouble() / vh)
        val scale = min(1.0, if (cropToSurface) cover else fit)
        return max(2, (vw * scale).roundToInt()) to max(2, (vh * scale).roundToInt())
    }

    private fun obtainFrame(width: Int, height: Int): Frame {
        while (true) {
            val frame = freeFrames.poll() ?: break
            if (frame.width == width && frame.height == height) return frame
            frame.bitmap.close()
        }
        // "rgb0" is R,G,B,X in memory, which is exactly Skia's RGB_888X: the X byte is never read.
        val info = ImageInfo(width, height, ColorType.RGB_888X, ColorAlphaType.OPAQUE)
        val rowBytes = (width * 4 + 63) / 64 * 64
        val bitmap = Bitmap()
        check(bitmap.allocPixels(info, rowBytes)) { "Could not allocate a ${width}x$height frame" }
        val address = bitmap.peekPixels()!!.use { it.addr }
        return Frame(bitmap, width, height, address, rowBytes)
    }

    /** UI thread: the newest finished frame. The previous one goes back to the render thread. */
    fun takeFrame(): Frame? {
        pendingFrame.getAndSet(null)?.let { next ->
            shownFrame?.let(freeFrames::add)
            shownFrame = next
        }
        return shownFrame
    }

    // endregion

    // region Events

    private fun eventLoop() {
        while (alive) {
            val event = api(null) { lib.mpv_wait_event(handle, 0.5) } ?: break
            when (event.getInt(LibMpv.EVENT_ID_OFFSET)) {
                LibMpv.EVENT_NONE -> Unit
                LibMpv.EVENT_SHUTDOWN -> break
                LibMpv.EVENT_LOG_MESSAGE -> event.getPointer(LibMpv.EVENT_DATA_OFFSET)?.let(::onLogMessage)
                LibMpv.EVENT_END_FILE -> event.getPointer(LibMpv.EVENT_DATA_OFFSET)?.let(::onEndFile)
                LibMpv.EVENT_FILE_LOADED -> {
                    clearError()
                    loading = false
                    background(::attachPendingSources)
                }
                LibMpv.EVENT_VIDEO_RECONFIG -> {
                    videoWidth = getString("dwidth")?.toIntOrNull() ?: 0
                    videoHeight = getString("dheight")?.toIntOrNull() ?: 0
                    requestRender(force = true)
                }
            }
        }
    }

    private fun onLogMessage(message: Pointer) {
        val level = message.getPointer(LibMpv.LOG_LEVEL_OFFSET)?.getString(0, "UTF-8").orEmpty()
        if (level != "warn" && level != "error" && level != "fatal") return
        val prefix = message.getPointer(LibMpv.LOG_PREFIX_OFFSET)?.getString(0, "UTF-8").orEmpty()
        val text = message.getPointer(LibMpv.LOG_TEXT_OFFSET)?.getString(0, "UTF-8")?.trim().orEmpty()
        if (text.isEmpty()) return
        Logger.w(TAG) { "[$prefix] $level: $text" }
        synchronized(logLock) {
            recentLogs.addLast("[$prefix] $text")
            while (recentLogs.size > 4) recentLogs.removeFirst()
        }
    }

    private fun onEndFile(endFile: Pointer) {
        if (endFile.getInt(LibMpv.END_FILE_REASON_OFFSET) != LibMpv.END_FILE_REASON_ERROR) return
        val reason = "[mpv] " + lib.mpv_error_string(endFile.getInt(LibMpv.END_FILE_ERROR_OFFSET))
        // Same message shape as iOS: the last few warnings, which usually name the real cause.
        errorMessage = synchronized(logLock) {
            (recentLogs.takeLast(3) + reason).distinct().joinToString("\n")
        }
        loading = false
    }

    private fun clearError() {
        synchronized(logLock) { recentLogs.clear() }
        errorMessage = null
    }

    // endregion

    // region Playback

    fun load(
        url: String,
        audioUrl: String?,
        headers: Map<String, String>,
        subtitles: List<StreamSubtitle>,
        startPositionMs: Long?,
    ) = background {
        clearError()
        loading = true
        activeHeaders = sanitizePlaybackHeaders(headers)
        applyHeaders(activeHeaders)
        pendingAudioUrl = audioUrl?.takeIf { it.isNotBlank() }
        pendingSubtitles = subtitles
        command(loadArgs(url, startPositionMs))
    }

    fun retry() = background {
        val path = getString("path") ?: return@background
        val positionMs = getString("time-pos")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        clearError()
        loading = true
        applyHeaders(activeHeaders)
        command(loadArgs(path, positionMs))
    }

    // loadfile <url> <flags> <index> <options> (mpv 0.38+); index -1 means "not given".
    private fun loadArgs(url: String, startPositionMs: Long?): Array<String> =
        if (startPositionMs != null && startPositionMs > 0) {
            arrayOf("loadfile", url, "replace", "-1", "start=" + secondsString(startPositionMs))
        } else {
            arrayOf("loadfile", url, "replace")
        }

    private fun attachPendingSources() {
        pendingAudioUrl?.let { command(arrayOf("audio-add", it, "select")) }
        pendingAudioUrl = null
        val subtitles = pendingSubtitles
        pendingSubtitles = emptyList()
        subtitles.forEach { addSubtitle(it, "auto") }
    }

    private fun addSubtitle(subtitle: StreamSubtitle, mode: String) {
        val subtitleHeaders = sanitizePlaybackHeaders(subtitle.headers)
        // mpv has one header set for every request, so subtitle-specific headers are swapped in
        // for the download and the stream's headers restored afterwards (as on iOS).
        if (subtitleHeaders.isNotEmpty()) applyHeaders(activeHeaders + subtitleHeaders)
        command(arrayOf("sub-add", subtitle.url, mode, subtitle.name ?: subtitle.language, subtitle.language))
        if (subtitleHeaders.isNotEmpty()) applyHeaders(activeHeaders)
    }

    private fun applyHeaders(headers: Map<String, String>) {
        // mpv sends its own User-Agent and Referer; passing them as extra fields would send two.
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
        setProperty("user-agent", userAgent ?: defaultUserAgent)
        setProperty("referrer", referer.orEmpty())
        val fields = headers
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) && !it.equals("Referer", ignoreCase = true) }
            .entries
            .sortedBy { it.key.lowercase() }
            .joinToString(",") { (key, value) -> "$key: " + value.replace("\\", "\\\\").replace(",", "\\,") }
        setProperty("http-header-fields", fields)
    }

    fun setPaused(paused: Boolean) = setProperty("pause", if (paused) "yes" else "no")

    fun seekTo(positionMs: Long) = command(arrayOf("seek", secondsString(positionMs.coerceAtLeast(0)), "absolute"))

    fun seekBy(offsetMs: Long) = command(arrayOf("seek", secondsString(offsetMs), "relative"))

    fun setSpeed(speed: Float) = setProperty("speed", speed.toString())

    fun setMuted(muted: Boolean) = setProperty("mute", if (muted) "yes" else "no")

    fun isPaused(): Boolean = getString("pause") == "yes"

    fun selectAudio(trackId: Int) = setProperty("aid", trackId.toString())

    fun applyAudioLanguages(languages: List<String>) {
        setProperty("alang", languages.joinToString(","))
        setProperty("aid", "auto")
    }

    fun selectSubtitle(trackId: Int) = setProperty("sid", if (trackId < 0) "no" else trackId.toString())

    fun addSubtitleUrl(url: String) = background { command(arrayOf("sub-add", url, "select")) }

    fun removeExternalSubtitles(selectTrackId: Int) = background {
        tracks().filter { it.type == "sub" && it.isExternal }
            .sortedByDescending { it.id }
            .forEach { command(arrayOf("sub-remove", it.id.toString())) }
        selectSubtitle(selectTrackId)
    }

    fun setSubtitleDelayMs(delayMs: Int) = setProperty("sub-delay", secondsString(delayMs.toLong()))

    fun applySubtitleStyle(
        textColor: String,
        backgroundColor: String,
        outlineColor: String,
        outlineSize: Float,
        bold: Boolean,
        fontSize: Float,
        subPos: Int,
        stripSdh: Boolean,
    ) {
        setProperty("sub-ass-override", "no")
        setProperty("sub-color", textColor)
        setProperty("sub-back-color", backgroundColor)
        setProperty("sub-outline-color", outlineColor)
        setProperty("sub-border-style", if (backgroundColor.startsWith("#00")) "outline-and-shadow" else "opaque-box")
        setProperty("sub-bold", if (bold) "yes" else "no")
        setProperty("sub-outline-size", outlineSize.toString())
        setProperty("sub-font-size", fontSize.toString())
        setProperty("sub-pos", subPos.toString())
        setProperty("sub-filter-sdh", if (stripSdh) "yes" else "no")
        setProperty("sub-filter-sdh-harder", if (stripSdh) "yes" else "no")
    }

    /** Same rules as the iOS bridge's refreshPlaybackState. */
    fun snapshot(): PlayerPlaybackSnapshot {
        val duration = getDouble("duration")
        val position = getDouble("time-pos")
        val cached = getDouble("demuxer-cache-time")
        val speed = getDouble("speed")
        val paused = getFlag("pause")
        val ended = getFlag("eof-reached")
        val idle = getFlag("core-idle")
        val seeking = getFlag("seeking")
        val buffering = getFlag("paused-for-cache")
        return PlayerPlaybackSnapshot(
            // mpv sits idle and unpaused after a failed load; that is the error, not loading.
            isLoading = errorMessage == null && (loading || (idle && !paused && !ended) || seeking || buffering),
            isPlaying = !paused && !idle && !ended,
            isEnded = ended,
            durationMs = (duration * 1000).toLong(),
            positionMs = (max(position, 0.0) * 1000).toLong(),
            bufferedPositionMs = (max(position + cached, 0.0) * 1000).toLong(),
            playbackSpeed = if (speed > 0) speed.toFloat() else 1f,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
        )
    }

    /** The file's chapters, read the same way as Android's libmpv engine. */
    fun chapters(): List<PlayerChapter> {
        val count = getString("chapter-list/count")?.toIntOrNull() ?: 0
        return (0 until count).mapNotNull { i ->
            val seconds = getString("chapter-list/$i/time")?.toDoubleOrNull() ?: return@mapNotNull null
            PlayerChapter(
                startMs = (seconds * 1000.0).toLong(),
                title = getString("chapter-list/$i/title")?.trim()?.takeIf { it.isNotBlank() },
            )
        }
    }

    fun tracks(): List<Track> {
        val count = getString("track-list/count")?.toIntOrNull() ?: 0
        var audioIndex = 0
        var subtitleIndex = 0
        return (0 until count).mapNotNull { i ->
            fun field(name: String) = getString("track-list/$i/$name")?.trim().orEmpty()
            val type = field("type")
            if (type != "audio" && type != "sub") return@mapNotNull null
            val index = if (type == "audio") audioIndex++ else subtitleIndex++
            val language = field("lang")
            Track(
                index = index,
                id = field("id").toIntOrNull() ?: return@mapNotNull null,
                type = type,
                label = MpvTrackLabels.format(
                    type = type,
                    index = index,
                    title = field("title"),
                    lang = language,
                    codec = field("codec"),
                    decoderDescription = field("decoder-desc"),
                    channels = field("demux-channels"),
                    channelCount = field("demux-channel-count").toIntOrNull() ?: 0,
                ),
                language = language,
                isSelected = field("selected") == "yes",
                isExternal = field("external") == "yes",
            )
        }
    }

    fun getString(name: String): String? = api(null) {
        val value = lib.mpv_get_property_string(handle, name) ?: return@api null
        try {
            value.getString(0, "UTF-8")
        } finally {
            lib.mpv_free(value)
        }
    }

    // endregion

    // region Plumbing

    private fun getDouble(name: String): Double = getString(name)?.toDoubleOrNull() ?: 0.0

    private fun getFlag(name: String): Boolean = getString(name) == "yes"

    private fun setProperty(name: String, value: String) {
        val status = api(0) { lib.mpv_set_property_string(handle, name, value) }
        if (status < 0) Logger.d(TAG) { "set $name failed: ${lib.mpv_error_string(status)}" }
    }

    private fun command(args: Array<String>) {
        val status = api(0) { lib.mpv_command(handle, args) }
        if (status < 0) Logger.d(TAG) { "${args.first()} failed: ${lib.mpv_error_string(status)}" }
    }

    private fun background(block: () -> Unit) {
        try {
            loader.execute(block)
        } catch (_: RejectedExecutionException) {
            // Only after destroy(): the player is gone, so there is nothing left to load.
        }
    }

    private inline fun <T> api(fallback: T, block: () -> T): T =
        apiLock.read { if (alive) block() else fallback }

    fun destroy() {
        if (!alive) return
        alive = false
        requestRender(force = false)
        loader.shutdownNow()
        // mpv_wait_event returns within its 0.5 s timeout; the render thread wakes from the signal.
        renderThread.join(2_000)
        eventThread.join(2_000)
        loader.awaitTermination(2, TimeUnit.SECONDS)
        apiLock.write {
            lib.mpv_render_context_set_update_callback(renderContext, null, null)
            // The render context must go before the core (render.h).
            lib.mpv_render_context_free(renderContext)
            lib.mpv_terminate_destroy(handle)
        }
        generateSequence { freeFrames.poll() }.forEach { it.bitmap.close() }
        pendingFrame.getAndSet(null)?.bitmap?.close()
        shownFrame?.bitmap?.close()
        shownFrame = null
    }

    // endregion

    companion object {
        /** Null when libmpv is missing or refuses to start; callers show the "player unavailable" error. */
        fun create(): MpvPlayer? {
            val lib = LibMpv.instance ?: return null
            val handle = lib.mpv_create() ?: return null
            listOf(
                "vo" to "libmpv",
                // The software renderer needs frames in RAM; auto-copy still decodes on the GPU.
                "hwdec" to "auto-copy",
                // Decode on mpv's own thread. Without it, a slow d3d11va copy-back stalled the
                // playback core: light video (360p, or 12 fps) played at 75-87% speed with audio
                // gaps on the T580's Intel GPU (2026-10-27), while software decoding kept real time.
                "vd-queue-enable" to "yes",
                "keep-open" to "yes",
                "idle" to "yes",
                "terminal" to "no",
                "input-default-bindings" to "no",
                "input-vo-keyboard" to "no",
                "osc" to "no",
                "ytdl" to "no",
                "audio-channels" to "auto",
                "audio-fallback-to-null" to "yes",
                "subs-match-os-language" to "yes",
                "subs-fallback" to "yes",
                "cache" to "yes",
                "demuxer-max-bytes" to "150MiB",
                "demuxer-max-back-bytes" to "75MiB",
            ).forEach { (name, value) -> lib.mpv_set_option_string(handle, name, value) }
            lib.mpv_request_log_messages(handle, "warn")
            if (lib.mpv_initialize(handle) < 0) {
                lib.mpv_terminate_destroy(handle)
                return null
            }
            val apiType = Memory(3).apply { setString(0, "sw", "US-ASCII") }
            val params = Memory(32).apply {
                clear()
                setInt(0, LibMpv.RENDER_PARAM_API_TYPE)
                setPointer(8, apiType)
                setInt(16, LibMpv.RENDER_PARAM_INVALID)
            }
            val contextRef = PointerByReference()
            val status = lib.mpv_render_context_create(contextRef, handle, params)
            if (status < 0) {
                Logger.w(TAG) { "mpv_render_context_create failed: ${lib.mpv_error_string(status)}" }
                lib.mpv_terminate_destroy(handle)
                return null
            }
            return MpvPlayer(lib, handle, contextRef.value).also { it.start() }
        }

        private fun secondsString(ms: Long): String = String.format(Locale.ROOT, "%.3f", ms / 1000.0)
    }
}
