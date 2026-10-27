package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Starts the external player process and reports back once it exits. Desktop players do not
 * hand back a playback position, so the result is null (same as an Android player that returns
 * no result extras) and progress is kept from what Nuvio already knew.
 */
@Composable
actual fun rememberExternalPlayerLauncher(
    onResult: (ExternalPlaybackResult?) -> Unit,
): (ExternalPlayerIntentResult.Success) -> Boolean {
    val currentOnResult by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    return remember {
        { intentResult: ExternalPlayerIntentResult.Success ->
            val command = (intentResult.intent as? List<*>)?.map { it.toString() }
            val process = command?.let(ExternalPlayerPlatform::launch)
            if (process != null) {
                scope.launch {
                    withContext(Dispatchers.IO) { process.waitFor() }
                    currentOnResult(null)
                }
                true
            } else {
                false
            }
        }
    }
}
