package com.nuvio.app.features.player.mpv

import com.nuvio.app.features.player.PlayerChapter
import com.nuvio.app.features.streams.StreamSubtitle
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Plays local media through the real libmpv (fetched by the build), so it needs no network.
 * test-clip.mkv is 90 s of ffmpeg's testsrc2 (640x360 12 fps H.264) with an English AAC track; the
 * extra audio comes from mpv's own av://lavfi: generator.
 */
class MpvPlayerTest {
    private lateinit var player: MpvPlayer

    @BeforeTest
    fun setUp() {
        player = MpvPlayer.create() ?: fail("libmpv did not load (nuvio.libmpv.dir=${System.getProperty("nuvio.libmpv.dir")})")
        player.surfaceWidth = 1280
        player.surfaceHeight = 720
    }

    @AfterTest
    fun tearDown() {
        player.destroy()
    }

    @Test
    fun playsVideoWithExtraAudioAndSubtitles() {
        val clip = File.createTempFile("nuvio-test", ".mkv").apply {
            deleteOnExit()
            MpvPlayerTest::class.java.getResourceAsStream("/mpv/test-clip.mkv")!!.use { input -> outputStream().use(input::copyTo) }
        }
        val srt = File.createTempFile("nuvio-test", ".srt").apply {
            deleteOnExit()
            writeText("1\n00:00:00,000 --> 00:01:30,000\nHello from the test\n")
        }
        player.load(
            url = clip.absolutePath,
            audioUrl = "av://lavfi:sine=frequency=440",
            headers = mapOf("User-Agent" to "NuvioTest/1.0", "X-Test" to "a,b"),
            subtitles = listOf(StreamSubtitle(url = srt.absolutePath, language = "en", name = "Test subs")),
            startPositionMs = 5_000,
        )

        val loaded = waitFor("the file to load") { player.snapshot().takeIf { !it.isLoading && it.durationMs > 0 } }
        assertTrue(loaded.durationMs in 89_900..90_100, "duration ${loaded.durationMs}")
        assertEquals(
            listOf(PlayerChapter(0, "Opening"), PlayerChapter(30_000, "Middle part")),
            player.chapters(),
        )
        assertTrue(loaded.positionMs >= 5_000, "started at ${loaded.positionMs} ms, not at the 5 s resume point")
        assertEquals(640, loaded.videoWidth)
        assertEquals(360, loaded.videoHeight)

        val frame = waitFor("a rendered frame") { onUiThread { player.takeFrame() } }
        // The GPU renderer draws at the 1280x720 surface; the software one never above the video's
        // own size, and Skia scales that up.
        val expected = if (player.rendersOnGpu) 1280 to 720 else 640 to 360
        assertEquals(expected, frame.width to frame.height)
        val colors = (0 until 16).map { i -> frame.bitmap.getColor(i * frame.width / 16 + 5, frame.height / 2) }.toSet()
        assertTrue(colors.size > 2, "frame looks blank: ${colors.map { Integer.toHexString(it) }}")

        val tracks = waitFor("the extra audio and subtitle tracks") {
            player.tracks().takeIf { list -> list.count { it.type == "audio" } == 2 && list.any { it.type == "sub" } }
        }
        val subtitle = tracks.single { it.type == "sub" }
        assertTrue(subtitle.isExternal)
        assertEquals("Test subs", subtitle.label.substringBefore(" ("))
        val (embedded, added) = tracks.filter { it.type == "audio" }.partition { !it.isExternal }
        assertEquals("English (Mono, AAC)", embedded.single().label)
        assertTrue(added.single().isSelected, "audio-add should select the separate audio track")

        player.selectSubtitle(subtitle.id)
        waitFor("the subtitle to be selected") {
            player.tracks().single { it.type == "sub" }.isSelected.takeIf { it }
        }

        player.seekTo(12_000)
        waitFor("the seek to 12 s") { player.snapshot().positionMs.takeIf { it in 11_900..13_000 } }

        player.setPaused(false)
        waitFor("playback to advance") { player.snapshot().takeIf { it.isPlaying && it.positionMs > 13_000 } }
        assertNull(player.errorMessage)

        player.removeExternalSubtitles(selectTrackId = -1)
        waitFor("the external subtitle to be removed") {
            player.tracks().none { it.type == "sub" }.takeIf { it }
        }
    }

    @Test
    fun reportsAnErrorForAMissingFile() {
        player.load(
            url = File(System.getProperty("java.io.tmpdir"), "nuvio-does-not-exist.mkv").absolutePath,
            audioUrl = null,
            headers = emptyMap(),
            subtitles = emptyList(),
            startPositionMs = null,
        )
        val message = waitFor("the error") { player.errorMessage }
        assertTrue("[mpv]" in message, message)
        assertTrue(!player.snapshot().isLoading)
    }

    private fun <T> onUiThread(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            Thread.sleep(50)
        }
        return assertNotNull(probe(), "timed out waiting for $what (error: ${player.errorMessage})")
    }
}
