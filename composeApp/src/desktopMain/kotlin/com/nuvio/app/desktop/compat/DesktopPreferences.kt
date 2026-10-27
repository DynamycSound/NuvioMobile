package com.nuvio.app.desktop.compat

import co.touchlab.kermit.Logger
import com.nuvio.app.desktop.DesktopPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The desktop key/value store behind every `*Storage` object.
 *
 * Each SharedPreferences name maps to one JSON file in `%APPDATA%\Nuvio\prefs`. Values are kept
 * in memory; writes are coalesced on a background thread (apply) or done immediately (commit),
 * always via write-to-temp + atomic rename so a crash never leaves a half-written file.
 */
object DesktopPreferences {
    private val log = Logger.withTag("DesktopPreferences")
    private val json = Json { prettyPrint = false }
    private val stores = HashMap<String, Store>()
    private val writer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nuvio-prefs-writer").apply { isDaemon = true }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ flushAll() }, "nuvio-prefs-flush"))
    }

    val directory: File get() = File(DesktopPaths.dataDir, "prefs")

    fun open(name: String): SharedPreferences = synchronized(stores) {
        stores.getOrPut(name) { Store(File(directory, "${sanitize(name)}.json")) }
    }

    fun flushAll() {
        synchronized(stores) { stores.values.toList() }.forEach { it.writeNow() }
    }

    /** Drops every preference file (used when the user wipes local account data). */
    fun deleteAll() {
        synchronized(stores) {
            stores.values.forEach { it.edit().clear().commit() }
        }
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private class Store(private val file: File) : SharedPreferences {
        private val lock = Any()
        private val values: MutableMap<String, Any> = load()
        private var pendingWrite: ScheduledFuture<*>? = null

        override val all: Map<String, *> get() = synchronized(lock) { values.toMap() }

        override fun getString(key: String, defValue: String?): String? =
            synchronized(lock) { values[key] as? String ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            synchronized(lock) { (values[key] as? Set<String>)?.toSet() ?: defValues }

        override fun getInt(key: String, defValue: Int): Int =
            synchronized(lock) { (values[key] as? Number)?.toInt() ?: defValue }

        override fun getLong(key: String, defValue: Long): Long =
            synchronized(lock) { (values[key] as? Number)?.toLong() ?: defValue }

        override fun getFloat(key: String, defValue: Float): Float =
            synchronized(lock) { (values[key] as? Number)?.toFloat() ?: defValue }

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            synchronized(lock) { values[key] as? Boolean ?: defValue }

        override fun contains(key: String): Boolean = synchronized(lock) { key in values }

        override fun edit(): SharedPreferences.Editor = EditorImpl()

        fun writeNow() {
            val snapshot = synchronized(lock) {
                pendingWrite?.cancel(false)
                pendingWrite = null
                values.toMap()
            }
            persist(snapshot)
        }

        private fun scheduleWrite() = synchronized(lock) {
            if (pendingWrite == null) {
                pendingWrite = writer.schedule({ writeNow() }, 250, TimeUnit.MILLISECONDS)
            }
        }

        private fun load(): MutableMap<String, Any> {
            val result = LinkedHashMap<String, Any>()
            if (!file.isFile) return result
            try {
                Json.parseToJsonElement(file.readText()).jsonObject.forEach { (key, element) ->
                    decode(element)?.let { result[key] = it }
                }
            } catch (error: Exception) {
                log.e(error) { "Could not read ${file.name}; keeping a copy and starting empty" }
                file.copyTo(File(file.parentFile, "${file.name}.corrupt"), overwrite = true)
            }
            return result
        }

        private fun persist(snapshot: Map<String, Any>) {
            val content = JsonObject(snapshot.mapValues { (_, value) -> encode(value) })
            file.parentFile.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(JsonObject.serializer(), content))
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }

        private inner class EditorImpl : SharedPreferences.Editor {
            private val changes = LinkedHashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String, value: String?) = put(key, value)
            override fun putStringSet(key: String, values: Set<String>?) = put(key, values?.toSet())
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)
            override fun remove(key: String) = put(key, null)
            override fun clear() = apply { clearAll = true }

            private fun put(key: String, value: Any?) = apply { changes[key] = value }

            private fun applyToMemory() = synchronized(lock) {
                if (clearAll) values.clear()
                changes.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }

            override fun commit(): Boolean {
                applyToMemory()
                return try {
                    writeNow()
                    true
                } catch (error: Exception) {
                    log.e(error) { "Failed to write ${file.name}" }
                    false
                }
            }

            override fun apply() {
                applyToMemory()
                scheduleWrite()
            }
        }
    }

    private fun encode(value: Any): JsonElement = when (value) {
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Set<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
        else -> error("Unsupported preference value: ${value::class}")
    }

    private fun decode(element: JsonElement): Any? = when (element) {
        is JsonArray -> element.map { (it as JsonPrimitive).content }.toSet()
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.content.toDoubleOrNull()
        }
        else -> null
    }
}
