package com.nuvio.app.features.player.mpv

import androidx.compose.ui.graphics.Color
import com.nuvio.app.features.player.SubtitleStyleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvSubtitleStyleTest {
    @Test
    fun aSemiTransparentBackgroundIsABoxInThatColourWithTheOutlineKept() {
        // A profile synced from Android (2026-10-27): white on 55 % black, 22 sp, offset 15.
        val style = MpvSubtitleStyle.from(
            SubtitleStyleState(
                textColor = Color.White,
                backgroundColor = Color(0x8C000000),
                outlineColor = Color.Black,
                fontSizeSp = 22,
                bottomOffset = 15,
            ),
        )
        assertEquals("background-box", style.borderStyle)
        assertEquals("#8C000000", style.backgroundColor)
        assertEquals("#FFFFFFFF", style.textColor)
        assertEquals("#FF000000", style.outlineColor)
        assertTrue(style.outlineSize > 0)
        assertTrue(style.boxMargin > 0)
        // 22 sp on a 411 dp tall phone is 5.4 % of the height; libass draws ascent + descent in it,
        // and a monitor gets two thirds of that.
        assertEquals(30.1, style.fontSize, 0.1)
        assertEquals(93, style.subPos)
    }

    @Test
    fun noBackgroundMeansOutlineOnlyAndNoShadow() {
        val style = MpvSubtitleStyle.from(SubtitleStyleState.DEFAULT)
        assertEquals("outline-and-shadow", style.borderStyle)
        assertEquals(0.0, style.boxMargin)
        assertEquals(93, MpvSubtitleStyle.from(SubtitleStyleState(bottomOffset = 20)).subPos)
        // Android caps the offset at 20 % of the height.
        assertEquals(75, MpvSubtitleStyle.from(SubtitleStyleState(bottomOffset = 1000)).subPos)
        assertEquals(0.0, MpvSubtitleStyle.from(SubtitleStyleState(outlineEnabled = false)).outlineSize)
    }
}
