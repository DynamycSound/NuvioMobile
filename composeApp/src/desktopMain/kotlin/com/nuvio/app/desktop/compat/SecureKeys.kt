package com.nuvio.app.desktop.compat

import com.nuvio.app.desktop.DesktopPaths
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop replacement for AndroidKeyStore AES keys.
 *
 * Each alias gets a random 256-bit AES key, stored in `%APPDATA%\Nuvio\keys` encrypted with
 * Windows DPAPI (CryptProtectData), so it can only be decrypted by the same Windows user.
 */
object SecureKeys {
    private val lock = Any()
    private val cache = HashMap<String, SecretKey>()

    fun getOrCreate(alias: String): SecretKey = synchronized(lock) {
        cache.getOrPut(alias) {
            val file = File(File(DesktopPaths.dataDir, "keys"), alias.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".key")
            val raw = if (file.isFile) {
                Crypt32Util.cryptUnprotectData(file.readBytes())
            } else {
                ByteArray(32).also(SecureRandom()::nextBytes).also { key ->
                    file.parentFile.mkdirs()
                    file.writeBytes(Crypt32Util.cryptProtectData(key))
                }
            }
            SecretKeySpec(raw, "AES")
        }
    }
}

/** android.util.Base64 subset used by the ported storage code. */
object Base64 {
    const val NO_WRAP = 2

    fun encodeToString(input: ByteArray, @Suppress("UNUSED_PARAMETER") flags: Int): String =
        java.util.Base64.getEncoder().encodeToString(input)

    fun decode(input: String, @Suppress("UNUSED_PARAMETER") flags: Int): ByteArray =
        java.util.Base64.getDecoder().decode(input)
}
