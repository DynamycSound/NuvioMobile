package com.nuvio.app.desktop.compat

import com.nuvio.app.desktop.DesktopPaths
import java.io.File

/**
 * Minimal stand-ins for android.content.Context / SharedPreferences.
 *
 * Most platform storage in this app is a thin SharedPreferences wrapper. Mirroring that API on
 * desktop lets the Android `actual` storage objects be ported nearly line-for-line, so upstream
 * changes to them can be carried over by diffing instead of rewriting.
 */
open class Context internal constructor() {
    val applicationContext: Context get() = this

    val filesDir: File get() = DesktopPaths.filesDir
    val cacheDir: File get() = DesktopPaths.cacheDir
    val noBackupFilesDir: File get() = DesktopPaths.filesDir
    val packageName: String get() = "com.nuvio.app"

    fun getSharedPreferences(name: String, @Suppress("UNUSED_PARAMETER") mode: Int): SharedPreferences =
        DesktopPreferences.open(name)

    companion object {
        const val MODE_PRIVATE = 0
    }
}

/** The single desktop "application context". */
object DesktopContext : Context()

interface SharedPreferences {
    val all: Map<String, *>
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}
