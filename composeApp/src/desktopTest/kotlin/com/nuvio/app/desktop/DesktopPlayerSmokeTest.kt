package com.nuvio.app.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.nuvio.app.features.player.PlayerScreen
import com.nuvio.app.features.player.mpv.MpvPlayer
import com.nuvio.app.features.streams.StreamSubtitle
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Plays a file over HTTP in the shared PlayerScreen, with the desktop mpv surface under the shared
 * controls. Opt-in like DesktopSmokeTest (`-Pnuvio.smoke`); `-Dnuvio.smoke.video=PATH` (passed via
 * the NUVIO_SMOKE_VIDEO env var) swaps the small bundled clip for a real 1080p file.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopPlayerSmokeTest {
    private val out = File(System.getProperty("nuvio.smoke.out") ?: "build/desktop-smoke/shots").also { it.mkdirs() }

    @Test
    fun playsInPlayerScreenWithSubtitlesAndControls() {
        assumeTrue(System.getProperty("nuvio.smoke") == "true")
        initializePlatformStorage()
        val video = testVideo()
        val srt = File(out, "subs.srt").apply {
            writeText("1\n00:00:00,500 --> 00:10:00,000\nDesktop subtitle test\n")
        }
        val server = MediaServer(mapOf("/video.mkv" to video, "/subs.srt" to srt))
        try {
            runDesktopComposeUiTest(width = 1280, height = 720, testTimeout = 5.minutes) {
                setContent {
                    PlayerScreen(
                        profileId = 1,
                        title = "Desktop player test",
                        sourceUrl = "${server.base}/video.mkv",
                        externalSubtitles = listOf(
                            StreamSubtitle(url = "${server.base}/subs.srt", language = "eng", name = "English"),
                        ),
                        providerName = "Local",
                        streamTitle = "Test clip",
                        streamSubtitle = null,
                        onBack = {},
                        parentMetaId = "tt0000001",
                        parentMetaType = "movie",
                    )
                }
                settle(5_000)
                val playing = snapshot("p1-playing")
                assertTrue(distinctColors(playing) > 8, "no video in the player area")

                onRoot().performTouchInput { click(center) }
                settle(500)
                waitForDescription("Pause")
                onNode(hasContentDescription("Pause") and hasClickAction()).performClick()
                settle(1_500)
                waitForDescription("Play")
                val paused = snapshot("p2-paused")
                val pausedAt = playbackPositionSeconds()
                assertTrue(pausedAt in 1..60, "paused at $pausedAt s; the clip should still be playing")
                settle(2_000)
                val stillPaused = snapshot("p3-still-paused")
                assertTrue(sameVideo(paused, stillPaused), "video kept moving after Pause")

                // Subtitle language defaults to None, so the stream's subtitle is picked by hand.
                // mpv redraws the paused frame when a subtitle appears, so this also covers that.
                onNode(hasContentDescription("Subtitles") and hasClickAction()).performClick()
                settle(1_500)
                onAllNodes(hasText("English", substring = true) and hasClickAction()).onFirst().performClick()
                settle(1_000)
                // "Built-in" is the stream's own subtitle, which mpv loaded as a track.
                onAllNodes(hasText("Built-in", substring = true) and hasClickAction()).onFirst().performClick()
                settle(1_000)
                snapshot("p4-subtitle-menu")
                // Outside the menu's columns, which closes it like a tap on the phone's scrim.
                onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.92f, height * 0.4f)) }
                settle(2_000)
                assertTrue(onAllNodes(hasText("Subtitle Delay")).fetchSemanticsNodes().isEmpty(), "subtitle menu stayed open")
                val withSubtitle = snapshot("p5-subtitle")
                assertTrue(!sameVideo(stillPaused, withSubtitle, band = 0.75..0.95), "no subtitle drawn on the paused frame")
            }
            println("player smoke: ${server.requests} HTTP requests, ranges=${server.rangeRequests}")
        } finally {
            server.stop()
        }
    }

    /** How fast the software renderer keeps up on this machine; prints numbers, fails only on a stall. */
    @Test
    fun measuresRenderThroughput() {
        assumeTrue(System.getProperty("nuvio.smoke") == "true")
        val video = testVideo()
        val player = MpvPlayer.create() ?: error("libmpv did not load")
        try {
            player.surfaceWidth = 1280
            player.surfaceHeight = 720
            player.load(video.absolutePath, null, emptyMap(), emptyList(), null)
            val deadline = System.currentTimeMillis() + 20_000
            while (player.snapshot().let { it.isLoading || it.positionMs < 1_000 } && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val cpuStart = os.processCpuTime
            val wallStart = System.nanoTime()
            val positionStart = player.snapshot().positionMs
            var frames = 0
            var last: MpvPlayer.Frame? = null
            var lastBitmapGeneration = -1
            var size = ""
            while (System.nanoTime() - wallStart < 10_000_000_000L) {
                SwingUtilities.invokeAndWait {
                    val frame = player.takeFrame()
                    val generation = frame?.bitmap?.generationId ?: -1
                    if (frame != null && (frame !== last || generation != lastBitmapGeneration)) {
                        frames++
                        last = frame
                        lastBitmapGeneration = generation
                        size = "${frame.width}x${frame.height}"
                    }
                }
                Thread.sleep(5)
            }
            val wallSeconds = (System.nanoTime() - wallStart) / 1e9
            val played = (player.snapshot().positionMs - positionStart) / 1000.0
            val cpu = (os.processCpuTime - cpuStart) / 1e9 / wallSeconds / Runtime.getRuntime().availableProcessors() * 100
            val decoder = player.getString("hwdec-current")
            val dropped = player.getString("frame-drop-count")
            val fps = player.getString("container-fps")
            val template = "player throughput: %.1f frames/s shown at %s (source %s fps), played %.1f s in %.1f s, " +
                "decoder drops %s, hwdec %s, process CPU %.0f%% of all cores"
            println(template.format(frames / wallSeconds, size, fps, played, wallSeconds, dropped, decoder, cpu))
            assertTrue(played > wallSeconds * 0.9, "playback fell behind real time")
            assertTrue(frames / wallSeconds > 10, "the renderer stalled")
        } finally {
            player.destroy()
        }
    }

    private fun testVideo(): File =
        (System.getProperty("nuvio.smoke.video") ?: System.getenv("NUVIO_SMOKE_VIDEO"))?.let(::File)
            ?: File(out, "test-clip.mkv").apply {
                DesktopPlayerSmokeTest::class.java.getResourceAsStream("/mpv/test-clip.mkv")!!
                    .use { input -> outputStream().use(input::copyTo) }
            }

    private fun distinctColors(image: BufferedImage): Int {
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 20) for (y in image.height / 4 until image.height * 3 / 4 step 20) {
            colors.add(image.getRGB(x, y) and 0xF0F0F0)
        }
        return colors.size
    }

    /** Compares a horizontal band of the picture (fractions of the height); the default skips the controls. */
    private fun sameVideo(a: BufferedImage, b: BufferedImage, band: ClosedRange<Double> = 0.25..0.75): Boolean {
        var differing = 0
        var total = 0
        val top = (a.height * band.start).toInt()
        val bottom = (a.height * band.endInclusive).toInt()
        for (x in 0 until a.width step 4) for (y in top until bottom step 4) {
            total++
            if (a.getRGB(x, y) != b.getRGB(x, y)) differing++
        }
        return differing < total / 50
    }

    /** Reads the "00:12 / 01:30" label of the shared controls. */
    private fun ComposeUiTest.playbackPositionSeconds(): Int {
        val label = onAllNodes(hasText(" / ", substring = true)).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text }
            .first { Regex("""\d+:\d+ / \d+:\d+""").matches(it) }
        val (minutes, seconds) = label.substringBefore(" /").split(":").map(String::toInt)
        return minutes * 60 + seconds
    }

    private fun ComposeUiTest.waitForDescription(description: String, timeoutMillis: Long = 20_000) {
        try {
            waitUntil(timeoutMillis = timeoutMillis) {
                onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            snapshot("failed-waiting-for-$description")
            throw error
        }
    }

    private fun ComposeUiTest.settle(millis: Long) {
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            mainClock.advanceTimeByFrame()
            Thread.sleep(16)
        }
    }

    private fun ComposeUiTest.snapshot(name: String): BufferedImage {
        val image = onRoot().captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(out, "$name.png"))
        println("smoke: saved $name")
        return image
    }

    /** Serves files with Range support, which mpv uses to seek. */
    private class MediaServer(files: Map<String, File>) {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        @Volatile var requests = 0
        @Volatile var rangeRequests = 0
        val base get() = "http://127.0.0.1:${server.address.port}"

        init {
            files.forEach { (path, file) -> server.createContext(path) { exchange -> serve(exchange, file) } }
            server.executor = java.util.concurrent.Executors.newCachedThreadPool()
            server.start()
        }

        fun stop() = server.stop(0)

        private fun serve(exchange: HttpExchange, file: File) {
            requests++
            exchange.use {
                val size = file.length()
                val range = Regex("bytes=(\\d+)-(\\d*)").find(exchange.requestHeaders.getFirst("Range").orEmpty())
                val start = range?.groupValues?.get(1)?.toLong() ?: 0L
                val end = range?.groupValues?.get(2)?.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                if (range != null) {
                    rangeRequests++
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
                }
                exchange.sendResponseHeaders(if (range != null) 206 else 200, end - start + 1)
                try {
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(start)
                        val buffer = ByteArray(1 shl 16)
                        var remaining = end - start + 1
                        while (remaining > 0) {
                            val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read <= 0) break
                            exchange.responseBody.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                } catch (_: java.io.IOException) {
                    // mpv closes connections when it seeks; the rest of that response is not needed.
                }
            }
        }
    }
}
