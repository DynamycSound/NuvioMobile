package com.nuvio.app.core.build

// Desktop builds compile src/fullCommonMain, so they follow the "full" flavor where possible.
actual object AppFeaturePolicy {
    actual val pluginsEnabled: Boolean = true
    actual val supportersContributorsPageEnabled: Boolean = true
    actual val donationActionsEnabled: Boolean = true
    actual val donationProgressEnabled: Boolean = false
    actual val accountDeletionEnabled: Boolean = false
    actual val personalMediaAddonCopyEnabled: Boolean = false
    // nuvio-engine (P2P) ships only as an Android library; there is no Windows build of it.
    actual val p2pEnabled: Boolean = false
    actual val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    actual val heroTrailerPlaybackSupported: Boolean = false
    actual val inAppUpdaterEnabled: Boolean = false
    actual val imdbRatingLogoEnabled: Boolean = true
    actual val mediaPlaybackForegroundServiceEnabled: Boolean = false
    actual val downloadForegroundServiceEnabled: Boolean = false
    actual val customServerConnectionsEnabled: Boolean = true
}
