package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import java.io.File
import java.net.URI

/**
 * External players on Windows: the usual desktop players, found in their default install
 * folders or on PATH. Each one gets the stream URL, title, start position, request headers and
 * subtitle files through its own command-line flags.
 */
internal actual object ExternalPlayerPlatform {
    private val log = Logger.withTag("ExternalPlayer")

    private data class Spec(
        val id: String,
        val name: String,
        val executables: List<String>,
        val buildArgs: (ExternalPlayerPlaybackRequest) -> List<String>,
    )

    private val specs = listOf(
        Spec("mpv", "mpv", listOf("mpv/mpv.exe", "mpv.net/mpvnet.exe", "mpv.exe", "mpvnet.exe"), ::mpvArgs),
        Spec("vlc", "VLC", listOf("VideoLAN/VLC/vlc.exe", "vlc.exe"), ::vlcArgs),
        Spec("mpc-hc", "MPC-HC", listOf("MPC-HC/mpc-hc64.exe", "mpc-hc64.exe"), ::mpcArgs),
        Spec("mpc-be", "MPC-BE", listOf("MPC-BE x64/mpc-be64.exe", "mpc-be64.exe"), ::mpcArgs),
        Spec("potplayer", "PotPlayer", listOf("DAUM/PotPlayer/PotPlayerMini64.exe", "PotPlayerMini64.exe"), ::potPlayerArgs),
    )

    private val installed: Map<String, File> by lazy {
        specs.mapNotNull { spec -> locate(spec.executables)?.let { spec.id to it } }.toMap()
    }

    fun initialize(@Suppress("UNUSED_PARAMETER") context: com.nuvio.app.desktop.compat.Context) = Unit

    actual fun defaultPlayerId(): String? = availablePlayers().firstOrNull()?.id

    actual fun availablePlayers(): List<ExternalPlayerApp> =
        specs.filter { it.id in installed }.map { ExternalPlayerApp(it.id, it.name) }

    actual fun open(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerOpenResult {
        val command = when (val result = buildIntent(request, playerId)) {
            is ExternalPlayerIntentResult.Success -> result.intent as List<*>
            ExternalPlayerIntentResult.NotConfigured -> return ExternalPlayerOpenResult.NotConfigured
            ExternalPlayerIntentResult.Failed -> return ExternalPlayerOpenResult.NoPlayerAvailable
        }
        return if (launch(command.map { it.toString() }) != null) {
            ExternalPlayerOpenResult.Opened
        } else {
            ExternalPlayerOpenResult.Failed
        }
    }

    /** The "intent" on desktop is the full command line (a List<String>). */
    actual fun buildIntent(
        request: ExternalPlayerPlaybackRequest,
        playerId: String?,
    ): ExternalPlayerIntentResult {
        if (installed.isEmpty()) return ExternalPlayerIntentResult.Failed
        val spec = specs.firstOrNull { it.id == playerId && it.id in installed }
            ?: specs.firstOrNull { it.id in installed }
            ?: return ExternalPlayerIntentResult.NotConfigured
        val executable = installed.getValue(spec.id)
        return ExternalPlayerIntentResult.Success(listOf(executable.absolutePath) + spec.buildArgs(request))
    }

    internal fun launch(command: List<String>): Process? = try {
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    } catch (error: Exception) {
        log.w(error) { "Could not start ${command.firstOrNull()}" }
        null
    }

    private fun locate(candidates: List<String>): File? {
        val roots = listOfNotNull(
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(x86)"),
            System.getenv("LOCALAPPDATA")?.let { "$it/Programs" },
            System.getenv("USERPROFILE")?.let { "$it/scoop/shims" },
        )
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
        for (candidate in candidates) {
            if (candidate.contains('/')) {
                roots.map { File(it, candidate) }.firstOrNull { it.isFile }?.let { return it }
            } else {
                path.map { File(it, candidate) }.firstOrNull { it.isFile }?.let { return it }
            }
        }
        return null
    }

    private fun ExternalPlayerPlaybackRequest.subtitlePaths(): List<String> =
        subtitles.orEmpty().map { it.url.toLocalPathOrUrl() }

    private fun String.toLocalPathOrUrl(): String =
        if (startsWith("file:")) runCatching { File(URI(this)).absolutePath }.getOrDefault(this) else this

    private fun ExternalPlayerPlaybackRequest.userAgent(): String? =
        sourceHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value

    private fun mpvArgs(request: ExternalPlayerPlaybackRequest): List<String> = buildList {
        add("--force-media-title=${request.buildPlayerTitle(includeEpisodeTitle = true)}")
        if (request.resumePositionMs > 0) add("--start=${request.resumePositionMs / 1000.0}")
        val headers = request.sourceHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        if (headers.isNotEmpty()) {
            // mpv splits this list on commas, so header values with commas are escaped.
            add("--http-header-fields=" + headers.entries.joinToString(",") { (k, v) -> "$k: ${v.replace(",", "\\,")}" })
        }
        request.userAgent()?.let { add("--user-agent=$it") }
        request.subtitlePaths().forEach { add("--sub-file=$it") }
        add("--")
        add(request.sourceUrl.toLocalPathOrUrl())
    }

    private fun vlcArgs(request: ExternalPlayerPlaybackRequest): List<String> = buildList {
        add(request.sourceUrl.toLocalPathOrUrl())
        add(":meta-title=${request.buildPlayerTitle(includeEpisodeTitle = true)}")
        if (request.resumePositionMs > 0) add(":start-time=${request.resumePositionMs / 1000.0}")
        request.userAgent()?.let { add(":http-user-agent=$it") }
        request.sourceHeaders.entries
            .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.let { add(":http-referrer=${it.value}") }
        request.subtitlePaths().firstOrNull()?.let { add(":sub-file=$it") }
    }

    private fun mpcArgs(request: ExternalPlayerPlaybackRequest): List<String> = buildList {
        add(request.sourceUrl.toLocalPathOrUrl())
        if (request.resumePositionMs > 0) {
            add("/startpos")
            add(formatPosition(request.resumePositionMs))
        }
        request.subtitlePaths().forEach {
            add("/sub")
            add(it)
        }
    }

    private fun potPlayerArgs(request: ExternalPlayerPlaybackRequest): List<String> = buildList {
        add(request.sourceUrl.toLocalPathOrUrl())
        if (request.resumePositionMs > 0) add("/seek=${formatPosition(request.resumePositionMs)}")
        request.userAgent()?.let { add("/user_agent=$it") }
        request.subtitlePaths().firstOrNull()?.let { add("/sub=$it") }
        add("/title=${request.buildPlayerTitle(includeEpisodeTitle = true)}")
    }

    private fun formatPosition(positionMs: Long): String {
        val totalSeconds = positionMs / 1000
        return "%02d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
    }
}
