package com.nuvio.app.features.servers

import com.nuvio.app.desktop.compat.Context
import com.nuvio.app.desktop.compat.SharedPreferences
import com.nuvio.app.desktop.compat.Base64
import com.nuvio.app.desktop.compat.SecureKeys
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal actual object ServerStorage {
    private const val keyAlias = "com.nuvio.media.servers.credentials.v1"
    private lateinit var preferences: SharedPreferences
    private val lock = Any()

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("nuvio_servers", Context.MODE_PRIVATE)
    }

    actual fun read(key: String): String? = synchronized(lock) {
        if (!::preferences.isInitialized) return@synchronized null
        val value = preferences.getString(key, null) ?: return@synchronized null
        try {
            val parts = value.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, decode(parts[0])))
            cipher.updateAAD("$keyAlias:$key".toByteArray(Charsets.UTF_8))
            cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            preferences.edit().remove(key).commit()
            null
        }
    }

    actual fun write(key: String, value: String?) = synchronized(lock) {
        if (!::preferences.isInitialized) return@synchronized
        val editor = preferences.edit()
        if (value == null) {
            editor.remove(key)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD("$keyAlias:$key".toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            editor.putString(key, "${encode(cipher.iv)}.${encode(encrypted)}")
        }
        if (!editor.commit()) throw IOException("Unable to save server credentials")
    }

    actual fun clear() = synchronized(lock) {
        if (!::preferences.isInitialized) return@synchronized
        if (!preferences.edit().clear().commit()) throw IOException("Unable to clear server credentials")
    }

    private fun secretKey(): SecretKey = SecureKeys.getOrCreate(keyAlias)

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
