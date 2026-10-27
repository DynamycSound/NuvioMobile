package com.nuvio.app.features.simkl

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.nuvio.app.desktop.androidDrawablePainter

@Composable
actual fun simklBrandPainter(asset: SimklBrandAsset): Painter =
    androidDrawablePainter(
        name = when (asset) {
            SimklBrandAsset.Glyph -> "simkl_logo_glyph"
            SimklBrandAsset.Wordmark -> "simkl_logo_wordmark"
        },
    )
