package com.nuvio.app.features.player.mpv

import kotlin.test.Test
import kotlin.test.assertEquals

class MpvTrackLabelsTest {
    private fun audio(title: String = "", channels: String, channelCount: Int, codec: String = "ac3") =
        MpvTrackLabels.format("audio", 0, title, "en", codec, "", channels, channelCount)

    @Test
    fun namesTheChannelLayoutLikeTheOtherPlayers() {
        // A 6-channel AC-3 track before decoding (Avatar's compatibility track, 2026-10-27).
        assertEquals("Compatibility track (5.1, AC-3)", audio("Compatibility track", "unknown6", 6))
        assertEquals("Compatibility track (5.1, AC-3)", audio("Compatibility track", "5.1(side)", 6))
        assertEquals("English (7.1, TrueHD)", audio(channels = "7.1", channelCount = 8, codec = "truehd"))
        assertEquals("English (Stereo, AC-3)", audio(channels = "stereo", channelCount = 2))
        assertEquals("English (3ch, AC-3)", audio(channels = "unknown3", channelCount = 3))
    }
}
