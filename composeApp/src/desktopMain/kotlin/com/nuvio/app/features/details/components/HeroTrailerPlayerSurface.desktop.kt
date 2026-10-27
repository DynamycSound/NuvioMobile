package com.nuvio.app.features.details.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

// Hero trailers are off on desktop (AppFeaturePolicy.heroTrailerPlaybackSupported = false);
// report an error so the hero falls back to its backdrop image.
@Composable
actual fun HeroTrailerPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    playWhenReady: Boolean,
    muted: Boolean,
    modifier: Modifier,
    onReady: () -> Unit,
    onEnded: () -> Unit,
    onError: () -> Unit,
) {
    LaunchedEffect(sourceUrl) { onError() }
}
