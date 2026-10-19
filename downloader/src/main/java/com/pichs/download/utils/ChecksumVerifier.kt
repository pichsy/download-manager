package com.pichs.download.utils

import com.pichs.download.config.Checksum
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal object ChecksumVerifier {

    fun verify(file: File, checksum: Checksum): Boolean {
        if (!file.isFile) return false
        return compute(file, checksum.type) == normalizeExpected(checksum.value)
    }

    fun normalizeExpected(value: String): String =
        value.trim().lowercase().substringAfterLast(':').removePrefix("0x")

    fun compute(file: File, type: Checksum.Type): String {
        val digest = MessageDigest.getInstance(
            when (type) {
                Checksum.Type.MD5 -> "MD5"
                Checksum.Type.SHA256 -> "SHA-256"
            }
        )
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    private fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hex = "0123456789abcdef"
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            chars[index * 2] = hex[value ushr 4]
            chars[index * 2 + 1] = hex[value and 0x0F]
        }
        return String(chars)
    }
}
