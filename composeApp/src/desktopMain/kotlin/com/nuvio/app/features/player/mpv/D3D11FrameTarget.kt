package com.nuvio.app.features.player.mpv

import co.touchlab.kermit.Logger
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * A Direct3D 11 device for mpv's GPU renderer (include/mpv/render_d3d11.h), and the texture each
 * frame is rendered into, copied back to RAM for Skia.
 *
 * On the GPU, mpv decodes (d3d11va, no copy-back), converts HDR, scales and draws subtitles;
 * only the finished frame at the player's size crosses to the CPU. The software renderer did all
 * of that on the CPU, and a 4K HDR stream dropped up to 5 frames a second on the T580 (2026-10-28).
 *
 * The device is free-threaded (render_d3d11.h): mpv's decoder thread uses it too, and the render
 * thread is the only other user of the immediate context.
 */
internal class D3D11FrameTarget private constructor(val device: Pointer, private val context: Pointer) {
    private var target: Pointer? = null
    private var staging: Pointer? = null
    private var width = 0
    private var height = 0
    private val mapped = Memory(16)

    /** The render target for a [width] x [height] frame, made again only when the size changes. */
    fun texture(width: Int, height: Int): Pointer {
        target?.takeIf { width == this.width && height == this.height }?.let { return it }
        releaseTextures()
        target = createTexture(width, height, USAGE_DEFAULT, BIND_RENDER_TARGET, 0)
        staging = createTexture(width, height, USAGE_STAGING, 0, CPU_ACCESS_READ)
        this.width = width
        this.height = height
        return target!!
    }

    /** Copies the last rendered frame into [rows] rows of [rowBytes] at [address]; false if the GPU refused. */
    fun copyTo(address: Long, rowBytes: Int, rows: Int): Boolean {
        val source = target ?: return false
        val copy = staging ?: return false
        context.call(COPY_RESOURCE, copy, source)
        // Map waits for the GPU to finish this frame.
        if (context.callInt(MAP, copy, 0, MAP_READ, 0, mapped) < 0) return false
        try {
            val data = mapped.getPointer(0)
            val pitch = mapped.getInt(8)
            val rowLength = minOf(rowBytes, pitch, width * 4)
            if (pitch == rowBytes) {
                moveMemory(Pointer(address), data, rowBytes.toLong() * rows)
            } else {
                for (row in 0 until rows) {
                    moveMemory(Pointer(address + row.toLong() * rowBytes), data.share(row.toLong() * pitch), rowLength.toLong())
                }
            }
        } finally {
            context.call(UNMAP, copy, 0)
        }
        return true
    }

    fun release() {
        releaseTextures()
        context.release()
        device.release()
    }

    private fun releaseTextures() {
        target?.release()
        staging?.release()
        target = null
        staging = null
        width = 0
        height = 0
    }

    private fun createTexture(width: Int, height: Int, usage: Int, bind: Int, cpuAccess: Int): Pointer {
        // D3D11_TEXTURE2D_DESC: Width, Height, MipLevels, ArraySize, Format, SampleDesc {Count, Quality},
        // Usage, BindFlags, CPUAccessFlags, MiscFlags.
        val desc = Memory(44).apply {
            intArrayOf(width, height, 1, 1, FORMAT_R8G8B8A8_UNORM, 1, 0, usage, bind, cpuAccess, 0)
                .forEachIndexed { index, value -> setInt(index * 4L, value) }
        }
        val texture = PointerByReference()
        val result = device.callInt(CREATE_TEXTURE_2D, desc, null, texture)
        check(result >= 0) { "CreateTexture2D ${width}x$height failed: 0x${result.toUInt().toString(16)}" }
        return texture.value
    }

    companion object {
        // Vtable slots, from mingw-w64's d3d11.h.
        private const val RELEASE = 2
        private const val CREATE_TEXTURE_2D = 5
        private const val MAP = 14
        private const val UNMAP = 15
        private const val COPY_RESOURCE = 47

        private const val DRIVER_TYPE_HARDWARE = 1
        private const val CREATE_DEVICE_VIDEO_SUPPORT = 0x800
        private const val SDK_VERSION = 7
        private const val FORMAT_R8G8B8A8_UNORM = 28
        private const val USAGE_DEFAULT = 0
        private const val USAGE_STAGING = 3
        private const val BIND_RENDER_TARGET = 0x20
        private const val CPU_ACCESS_READ = 0x20000
        private const val MAP_READ = 1
        private const val E_INVALIDARG = 0x80070057.toInt()

        private val moveMemoryFunction by lazy { NativeLibrary.getInstance("kernel32").getFunction("RtlMoveMemory") }

        /** Null when there is no hardware Direct3D 11 device with video decoding, e.g. over Remote Desktop. */
        fun create(): D3D11FrameTarget? = try {
            createDevice()
        } catch (error: Throwable) {
            Logger.w("NuvioDesktopPlayer", error) { "No Direct3D 11 device" }
            null
        }

        private fun createDevice(): D3D11FrameTarget? {
            val createDevice = NativeLibrary.getInstance("d3d11").getFunction("D3D11CreateDevice")
            // 11_1 first; runtimes that predate it reject the whole list, so it is retried without it.
            val levels = listOf(intArrayOf(0xb100, 0xb000, 0xa100, 0xa000), intArrayOf(0xb000, 0xa100, 0xa000))
            for (list in levels) {
                val levelArray = Memory(4L * list.size).apply { list.forEachIndexed { i, level -> setInt(i * 4L, level) } }
                val device = PointerByReference()
                val context = PointerByReference()
                val result = createDevice.invokeInt(
                    arrayOf(
                        null, DRIVER_TYPE_HARDWARE, null, CREATE_DEVICE_VIDEO_SUPPORT,
                        levelArray, list.size, SDK_VERSION, device, IntByReference(), context,
                    ),
                )
                if (result >= 0) return D3D11FrameTarget(device.value, context.value)
                if (result != E_INVALIDARG) {
                    Logger.w("NuvioDesktopPlayer") { "D3D11CreateDevice failed: 0x${result.toUInt().toString(16)}" }
                    return null
                }
            }
            return null
        }

        private fun moveMemory(destination: Pointer, source: Pointer, length: Long) {
            moveMemoryFunction.invokeVoid(arrayOf(destination, source, length))
        }

        private fun Pointer.method(slot: Int): Function = Function.getFunction(getPointer(0).getPointer(slot * 8L))

        private fun Pointer.callInt(slot: Int, vararg args: Any?): Int = method(slot).invokeInt(arrayOf(this, *args))

        private fun Pointer.call(slot: Int, vararg args: Any?) = method(slot).invokeVoid(arrayOf(this, *args))

        private fun Pointer.release() {
            method(RELEASE).invokeInt(arrayOf(this))
        }
    }
}
