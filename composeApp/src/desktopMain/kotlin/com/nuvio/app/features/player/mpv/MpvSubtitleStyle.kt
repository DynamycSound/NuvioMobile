package com.nuvio.app.features.player.mpv

import androidx.compose.ui.graphics.Color
import com.nuvio.app.features.player.SubtitleStyleState
import kotlin.math.roundToInt

/**
 * The shared subtitle settings as mpv options, drawn the way Android's default player (ExoPlayer's
 * SubtitleView) draws them, since most people set them up on their phone: the background colour
 * as a box behind the text, the outline on top, and the size in sp of a phone held sideways.
 *
 * mpv sizes are "scaled pixels at a window height of 720" and grow with the window.
 */
internal data class MpvSubtitleStyle(
    val textColor: String,
    val backgroundColor: String,
    val outlineColor: String,
    val borderStyle: String,
    val outlineSize: Double,
    val boxMargin: Double,
    val bold: Boolean,
    val fontSize: Double,
    val subPos: Int,
    val stripSdh: Boolean,
) {
    companion object {
        /** Height of a typical phone in landscape (1080 px at 420 dpi), the screen the sp sizes were picked on. */
        private const val PHONE_LANDSCAPE_HEIGHT_DP = 411.0
        private const val SCALED_PX_PER_DP = 720.0 / PHONE_LANDSCAPE_HEIGHT_DP
        // libass fits ascent + descent into the font size, Android fits the em: Roboto's line is ~1.17 em.
        private const val LIBASS_SIZE_PER_ANDROID_TEXT_SIZE = 1.17

        fun from(style: SubtitleStyleState): MpvSubtitleStyle {
            val fontSize = style.fontSizeSp * SCALED_PX_PER_DP * LIBASS_SIZE_PER_ANDROID_TEXT_SIZE
            val hasBackground = style.backgroundColor.alpha > 0f
            // ExoPlayer's SubtitleView: bottom padding is 2/3 of its default 8 %, plus the offset in tenths of a percent.
            val bottomFraction = (0.08 * 2 / 3 + (style.bottomOffset / 1000.0).coerceIn(0.0, 0.2)).coerceIn(0.0, 0.4)
            return MpvSubtitleStyle(
                textColor = style.textColor.toMpvColor(),
                backgroundColor = style.backgroundColor.toMpvColor(),
                outlineColor = style.outlineColor.toMpvColor(),
                // background-box colours the box with the background colour and still draws the
                // outline; opaque-box would paint the box in the outline colour (mpv options.rst).
                borderStyle = if (hasBackground) "background-box" else "outline-and-shadow",
                outlineSize = if (style.outlineEnabled) style.outlineWidth * SCALED_PX_PER_DP else 0.0,
                // In background-box the shadow offset is the box's margin; otherwise it would draw a shadow.
                boxMargin = if (hasBackground) fontSize * 0.1 else 0.0,
                bold = style.bold,
                fontSize = fontSize,
                subPos = (100 * (1 - bottomFraction)).roundToInt(),
                stripSdh = style.stripSdh,
            )
        }

        /** mpv reads `#AARRGGBB`. */
        private fun Color.toMpvColor(): String {
            fun Float.hex() = (this * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
            return "#" + alpha.hex() + red.hex() + green.hex() + blue.hex()
        }
    }
}
