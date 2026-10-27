package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.nuvio.app.desktop.androidDrawablePainter

@Composable
actual fun appIconPainter(icon: AppIconResource): Painter =
    androidDrawablePainter(
        name = when (icon) {
            AppIconResource.PlayerPlay -> "ic_player_play"
            AppIconResource.PlayerPause -> "ic_player_pause"
            AppIconResource.PlayerAspectRatio -> "ic_player_aspect_ratio"
            AppIconResource.PlayerSubtitles -> "ic_player_subtitles"
            AppIconResource.PlayerAudioFilled -> "ic_player_audio_filled"
            AppIconResource.PlayerSource -> "ic_player_source"
            AppIconResource.PlayerEpisodes -> "ic_player_episodes"
            AppIconResource.LibraryAddPlus -> "library_add_plus"
        }
    )
