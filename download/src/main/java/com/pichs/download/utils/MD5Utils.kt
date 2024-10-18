package com.pichs.download.utils

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * md5 tool class
 *
 * @author Crane
 */
object MD5Utils {
    /**
     * MD5 encode
     *
     * @param data
     * @return
     */
    @Synchronized
    fun md5(str: String): String {
        try {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(str.toByteArray(charset("utf-8")))
            return encodeHex(digest.digest())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    /**
     * 文件校验md5
     */
    @Synchronized
    fun fileCheckMD5(filename: String?): String {
        return if (filename.isNullOrEmpty()) "" else fileCheckMD5(File(filename))
    }

    @Synchronized
    fun fileCheckMD5(file: File?): String {
        if (file == null) return ""
        if (!file.exists()) return ""
        try {
            val digest = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return encodeHex(digest.digest())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    private fun encodeHex(bytes: ByteArray): String {
        val buf = StringBuffer(bytes.size * 2)
        var i: Int = 0
        while (i < bytes.size) {
            if (bytes[i].toInt() and 0xff < 0x10) {
                buf.append("0")
            }
            buf.append((bytes[i].toInt() and 0xff).toLong().toString(16))
            i++
        }
        return buf.toString()
    }
}