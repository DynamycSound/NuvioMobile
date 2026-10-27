package com.nuvio.app.features.trakt

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.nuvio.app.desktop.androidDrawablePainter

@Composable
actual fun traktBrandPainter(asset: TraktBrandAsset): Painter =
    androidDrawablePainter(
        name = when (asset) {
            TraktBrandAsset.Glyph -> "trakt_tv_favicon"
            TraktBrandAsset.Wordmark -> "trakt_logo_wordmark"
        },
    )
