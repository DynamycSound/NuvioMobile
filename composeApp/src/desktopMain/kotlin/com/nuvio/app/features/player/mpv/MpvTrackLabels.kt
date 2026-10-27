package com.nuvio.app.features.player.mpv

import java.util.Locale

/**
 * Track names in the same shape as the iOS player (MPVPlayerBridge.swift formatTrackTitle):
 * "English (5.1, E-AC-3)", falling back to "Track 2" / "Subtitle 3".
 */
internal object MpvTrackLabels {
    fun format(
        type: String,
        index: Int,
        title: String,
        lang: String,
        codec: String,
        decoderDescription: String,
        channels: String,
        channelCount: Int,
    ): String {
        val base = title.ifBlank { null }
            ?: languageName(lang)
            ?: if (type == "sub") "Subtitle ${index + 1}" else "Track ${index + 1}"
        val codecName = codecName(codec) ?: codecName(decoderDescription)
        val channelName = if (type == "audio") channelLayoutName(channels, channelCount) else null
        val details = listOfNotNull(channelName, codecName)
            .filterNot { base.contains(it, ignoreCase = true) }
        return if (details.isEmpty()) base else "$base (${details.joinToString(", ")})"
    }

    private fun languageName(code: String): String? {
        val trimmed = code.trim().ifEmpty { return null }
        // Java resolves both ISO 639-1 ("en") and 639-2 ("eng") codes; unknown codes come back unchanged.
        return Locale.forLanguageTag(trimmed).getDisplayLanguage(Locale.getDefault()).ifBlank { trimmed }
    }

    private fun channelLayoutName(channels: String, channelCount: Int): String? {
        // mpv names a layout it cannot identify "unknown6", and qualifies known ones ("5.1(side)").
        val normalized = channels.trim().substringBefore('(')
        if (normalized.isNotEmpty() && !normalized.startsWith("unknown")) {
            return when (normalized.lowercase()) {
                "mono" -> "Mono"
                "stereo" -> "Stereo"
                else -> normalized
            }
        }
        return when {
            channelCount == 1 -> "Mono"
            channelCount == 2 -> "Stereo"
            channelCount == 6 -> "5.1"
            channelCount == 8 -> "7.1"
            channelCount > 0 -> "${channelCount}ch"
            else -> null
        }
    }

    private fun codecName(value: String): String? {
        val raw = value.trim().ifEmpty { return null }
        val codec = raw.lowercase()
        return when {
            "eac3" in codec || "e-ac-3" in codec || "e ac-3" in codec ->
                if ("joc" in codec || "atmos" in codec) "E-AC-3-JOC" else "E-AC-3"
            "truehd" in codec || "true hd" in codec -> "TrueHD"
            "ac3" in codec || "ac-3" in codec -> "AC-3"
            "dts-hd" in codec || "dtshd" in codec || "dts hd" in codec -> "DTS-HD"
            "dts" in codec || codec == "dca" -> "DTS"
            "aac" in codec -> "AAC"
            "mp3" in codec || "mpeg audio" in codec -> "MP3"
            "mp2" in codec -> "MP2"
            "opus" in codec -> "Opus"
            "vorbis" in codec -> "Vorbis"
            "flac" in codec -> "FLAC"
            "alac" in codec -> "ALAC"
            "pcm" in codec || "wav" in codec -> "WAV"
            "amr_wb" in codec || "amr-wb" in codec -> "AMR-WB"
            "amr_nb" in codec || "amr-nb" in codec -> "AMR-NB"
            "amr" in codec -> "AMR"
            "iamf" in codec -> "IAMF"
            "mpegh" in codec || "mpeg-h" in codec -> "MPEG-H"
            "pgs" in codec || "hdmv" in codec -> "PGS"
            "subrip" in codec || codec == "srt" -> "SRT"
            "ass" in codec || "ssa" in codec -> "SSA"
            "webvtt" in codec || codec == "vtt" -> "VTT"
            "ttml" in codec -> "TTML"
            "mov_text" in codec || "tx3g" in codec -> "TX3G"
            "dvb" in codec -> "DVB"
            else -> raw
        }
    }
}
