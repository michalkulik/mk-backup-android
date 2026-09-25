package com.michalkulik.mkbackup.backup

import java.io.InputStream
import java.security.MessageDigest

/** Streaming SHA-256 helpers. Content is never buffered in full, so huge videos are fine. */
object Hashing {

    private const val BUFFER_SIZE = 64 * 1024

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    fun sha256Of(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it.toByteArray(Charsets.UTF_8)) }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
}
