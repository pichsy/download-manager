package com.pichs.download.utils

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
    private var digest: MessageDigest? = null

    /**
     * MD5 encode
     *
     * @param data
     * @return
     */
    @Synchronized
    fun md5(data: String): String {
        if (digest == null) {
            try {
                digest = MessageDigest.getInstance("MD5")
                return encodeHex(digest!!.digest())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        try {
            if (digest == null) {
                return ""
            }
            digest?.update(data.toByteArray(charset("utf-8")))
            return encodeHex(digest!!.digest())
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
        if (digest == null) {
            try {
                digest = MessageDigest.getInstance("MD5")
            } catch (e: NoSuchAlgorithmException) {
                e.printStackTrace()
            }
        }
        try {
            val fis: InputStream = FileInputStream(filename)
            val buffer = ByteArray(1024)
            var numRead: Int
            do {
                numRead = fis.read(buffer)
                if (numRead > 0) {
                    digest?.update(buffer, 0, numRead)
                }
            } while (numRead != -1)
            fis.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return if (digest != null) {
            encodeHex(digest!!.digest())
        } else {
            ""
        }
    }

    private fun encodeHex(bytes: ByteArray): String {
        val buf = StringBuffer(bytes.size * 2)
        var i: Int = 0
        while (i < bytes.size) {
            if (bytes[i].toInt() and 0xff < 0x10) {
                buf.append("0")
            }
            buf.append(java.lang.Long.toString((bytes[i].toInt() and 0xff).toLong(), 16))
            i++
        }
        return buf.toString()
    }
}