package com.nuvio.app.features.player.mpv

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * The parts of libmpv's client and render API (include/mpv/client.h, render.h) the desktop
 * player uses. Constants and struct offsets were read from the headers shipped with the
 * shinchiro 20261021 build that desktop.gradle bundles.
 */
@Suppress("FunctionName")
internal interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_free(data: Pointer?)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int

    /** JNA passes a String[] as a NULL-terminated char**, which is what mpv expects. */
    fun mpv_command(ctx: Pointer, args: Array<String>): Int
    fun mpv_command_async(ctx: Pointer, replyUserdata: Long, args: Array<String>): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer

    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: UpdateCallback?, callbackCtx: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_free(ctx: Pointer)

    fun interface UpdateCallback : Callback {
        fun invoke(callbackCtx: Pointer?)
    }

    companion object {
        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_LOG_MESSAGE = 2
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_VIDEO_RECONFIG = 17
        const val EVENT_PLAYBACK_RESTART = 21

        const val END_FILE_REASON_ERROR = 4

        const val RENDER_PARAM_INVALID = 0
        const val RENDER_PARAM_API_TYPE = 1
        const val RENDER_PARAM_SW_SIZE = 17
        const val RENDER_PARAM_SW_FORMAT = 18
        const val RENDER_PARAM_SW_STRIDE = 19
        const val RENDER_PARAM_SW_POINTER = 20
        const val RENDER_PARAM_D3D11_INIT_PARAMS = 21
        const val RENDER_PARAM_D3D11_FBO = 22

        /** Bit of mpv_render_context_update()'s result: a new video frame should be rendered. */
        const val RENDER_UPDATE_FRAME = 1L

        // struct mpv_event { int event_id; int error; uint64_t reply_userdata; void *data; }
        const val EVENT_ID_OFFSET = 0L
        const val EVENT_DATA_OFFSET = 16L

        // struct mpv_event_end_file { int reason; int error; ... }
        const val END_FILE_REASON_OFFSET = 0L
        const val END_FILE_ERROR_OFFSET = 4L

        // struct mpv_event_log_message { const char *prefix, *level, *text; ... }
        const val LOG_PREFIX_OFFSET = 0L
        const val LOG_LEVEL_OFFSET = 8L
        const val LOG_TEXT_OFFSET = 16L

        private const val DLL_NAME = "libmpv-2.dll"

        /** Null when the DLL is missing or fails to load; the player then reports an error. */
        val instance: LibMpv? by lazy {
            val dll = candidateDirs().map { File(it, DLL_NAME) }.firstOrNull(File::isFile)
                ?: return@lazy null
            runCatching {
                Native.load(
                    dll.absolutePath,
                    LibMpv::class.java,
                    mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"),
                )
            }.getOrNull()
        }

        // Packaged apps get compose.application.resources.dir; `gradlew run` and tests can point
        // nuvio.libmpv.dir at build/libmpv instead.
        private fun candidateDirs(): List<String> = listOfNotNull(
            System.getProperty("compose.application.resources.dir"),
            System.getProperty("nuvio.libmpv.dir"),
            System.getenv("NUVIO_LIBMPV_DIR"),
        )
    }
}
