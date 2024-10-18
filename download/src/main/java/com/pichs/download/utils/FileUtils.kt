package com.pichs.download.utils

import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object FileUtils {
    private const val MAX_FILENAME_LENGTH = 32
    private const val TRUNCATED_NAME_LENGTH = 20
    private const val MD5_SUFFIX_LENGTH = 32

    /**
     * 检查并创建文件
     * @return 文件是否创建成功
     * 如果文件被建成文件夹了，则删除目录
     * 重新建一个文件。
     * 如果目录不存在，则创建目录。
     */
    fun checkAndCreateFileSafe(file: File): Boolean {
        try {// 文件处理。
            if (!file.exists()) {
                return createFile(file)
            } else if (file.isDirectory) {
                // 如果文件被建成文件夹了，则删除目录
                checkAndDeleteFile(file)
                return createFile(file)
            } else {
                return true
            }
        } catch (e: Exception) {
            DownloadLog.e(e) { "checkAndCreateFileSafe: 创建文件失败:${file.absolutePath}" }
            return false
        }
    }

    /**
     * 检查并创建文件
     * @return 文件是否创建成功
     * 如果文件被建成文件夹了，则删除目录
     * 重新建一个文件。
     * 如果目录不存在，则创建目录。
     */
    fun checkAndCreateFileSafeWithLength(file: File, length: Long): Boolean {
        try {// 文件处理。
            if (!file.exists()) {
                if (createFile(file)) {
                    // 预分配文件大小
                    RandomAccessFile(file, "rw").use { randomAccessFile ->
                        randomAccessFile.setLength(length)
                    }
                    return true
                }
            } else if (file.isDirectory) {
                // 如果文件被建成文件夹了，则删除目录
                checkAndDeleteFile(file)
                if (createFile(file)) {
                    // 预分配文件大小
                    RandomAccessFile(file, "rw").use { randomAccessFile ->
                        randomAccessFile.setLength(length)
                    }
                    return true
                }
            } else {
                // 文件大小文件肯定存在了并且是个文件
                if (file.length() != length) {
                    return true
                } else {
                    checkAndDeleteFile(file)
                    if (createFile(file)) {
                        // 预分配文件大小
                        RandomAccessFile(file, "rw").use { randomAccessFile ->
                            randomAccessFile.setLength(length)
                        }
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            DownloadLog.e(e) { "checkAndCreateFileSafe: 创建文件失败:${file.absolutePath}" }
            return false
        }
        return false
    }

    /**
     * 创建文件
     */
    fun createFile(file: File): Boolean {
        try {
            if (!file.exists()) {
                if (file.parentFile?.exists() != true) {
                    file.parentFile?.mkdirs()
                }
                return file.createNewFile()
            } else {
                return true
            }
        } catch (e: Exception) {
            DownloadLog.e(e) { "createFile: 创建文件失败:${file.absolutePath}" }
        }
        return false
    }


    /**
     * 删除文件或者文件夹
     * @return 是否删除成功
     */
    fun checkAndDeleteFile(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            return checkAndDeleteDir(file)
        }
        return file.delete()
    }

    /**
     * 删除文件夹
     */
    private fun checkAndDeleteDir(file: File): Boolean {
        return file.deleteRecursively()
    }

    /**
     * 生成文件名,带后缀的。
     */
    fun generateFilename(fileName: String?, url: String, contentType: String?): String {
        // 判断filename带不带后缀，如果带后缀，直接返回
        if (fileName != null && fileName.contains('.')) return fileName
        // 如果不带后缀，则从url中获取后缀后拼接到fileName后面

        val decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        val lastPart = decodedUrl.substringAfterLast('/')

        val extension = getExtensionFromContentType(contentType) ?: lastPart.substringAfterLast('.', "")

        if (!fileName.isNullOrEmpty() && fileName.isNotBlank()) {
            return addExtensionIfMissing(fileName, extension)
        } else if (lastPart.isEmpty()) {
            return "${MD5Utils.md5(url)}${if (extension.isNotEmpty()) ".$extension" else ""}"
        } else {
            val sanitizedName = sanitizeFilename(lastPart)
            if (sanitizedName.length > MAX_FILENAME_LENGTH) {
                return truncateFilename(lastPart, extension)
            } else {
                return addExtensionIfMissing(sanitizedName, extension)
            }
        }
    }

    private fun sanitizeFilename(filename: String): String {
        return filename.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim()
    }

    private fun truncateFilename(nameWithoutExtension: String, extension: String): String {
        val truncatedName = nameWithoutExtension.take(TRUNCATED_NAME_LENGTH)
        val md5Suffix = MD5Utils.md5(nameWithoutExtension).take(MD5_SUFFIX_LENGTH)
        return if (extension.isNotEmpty()) {
            "$truncatedName-$md5Suffix.$extension"
        } else {
            "$truncatedName-$md5Suffix"
        }
    }

    private fun getExtensionFromContentType(contentType: String?): String? {
        return contentType?.let { ContentTypeMapper.getExtensionFromContentType(it) }
    }

    private fun addExtensionIfMissing(filename: String, extension: String): String {
        return if (extension.isNotEmpty() && !filename.endsWith(".$extension", ignoreCase = true)) {
            "$filename.$extension"
        } else {
            filename
        }
    }

}