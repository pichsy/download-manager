package com.pichs.download.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.ByteBuffer

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
    suspend fun checkAndCreateFileSafe(file: File): Boolean {
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
     * 检查文件是否存在
     */
    suspend fun isFileExists(file: File): Boolean {
        return withContext(Dispatchers.IO) {
            file.exists()
        }
    }

    /**
     * 检查文件是否完整或有效
     * @param file 要检查的文件
     * @param totalLength 预期的文件大小（字节）
     * @param fileMD5 预期的 MD5 值（可选）
     * @return 文件是否完整有效
     */
    suspend fun isFileValid(file: File, totalLength: Long, fileMD5: String? = null): Boolean {
        return withContext(Dispatchers.IO) {
            if (!file.exists() || !file.isFile) {
                return@withContext false
            }
            // 检查文件大小
            if (file.length() != totalLength) {
                return@withContext false
            }
            // 如果提供了 MD5，则验证 MD5
            if (fileMD5 != null) {
                val actualMd5 = MD5Utils.fileCheckMD5(file)
                if (actualMd5 != fileMD5) {
                    return@withContext false
                }
            }
            true
        }
    }


    /**
     * 检查并创建文件
     * @return 文件是否创建成功
     * 如果文件被建成文件夹了，则删除目录
     * 重新建一个文件。
     * 如果目录不存在，则创建目录。
     */
    suspend fun checkAndCreateFileSafeWithLength(file: File, length: Long): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                when {
                    file.exists() && file.isFile && file.length() == length -> true
                    !file.exists() || (file.exists() && !file.isFile) -> {
                        if (file.exists()) {
                            checkAndDeleteFile(file)
                        }
                        if (createFile(file)) {
                            allocateFileSize(file, length)
                            true
                        } else {
                            false
                        }
                    }

                    else -> {
                        allocateFileSize(file, length)
                        true
                    }
                }
            } catch (e: Exception) {
                DownloadLog.e(e) { "checkAndCreateFileSafe: 创建文件失败:${file.absolutePath}" }
                false
            }
        }
    }

    private fun allocateFileSize(file: File, length: Long) {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            channel.truncate(length)
        }
    }

    /**
     * 创建文件
     */
    suspend fun createFile(file: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) {
                    if (file.parentFile?.exists() != true) {
                        file.parentFile?.mkdirs()
                    }
                    return@withContext file.createNewFile()
                } else {
                    return@withContext true
                }
            } catch (e: Exception) {
                DownloadLog.e(e) { "createFile: 创建文件失败:${file.absolutePath}" }
            }
            return@withContext false
        }
    }


    /**
     * 删除文件或者文件夹
     * @return 是否删除成功
     */
    private suspend fun checkAndDeleteFile(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            return checkAndDeleteDir(file)
        }
        return file.delete()
    }

    /**
     * 删除文件夹
     */
    private suspend fun checkAndDeleteDir(file: File): Boolean {
        return file.deleteRecursively()
    }

    /**
     * 生成文件名,带后缀的。
     */
    fun generateFilename(fileName: String?, url: String, contentType: String?): String {
        val trimFileName = fileName?.trim()
        // 判断filename带不带后缀，如果带后缀，直接返回
        if (trimFileName != null && trimFileName.contains('.')) return trimFileName
        // 如果不带后缀，则从url中获取后缀后拼接到fileName后面

        val decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        val lastPart = decodedUrl.substringAfterLast('/')

        val extension = getExtensionFromContentType(contentType) ?: lastPart.substringAfterLast('.', "")

        if (!trimFileName.isNullOrEmpty() && trimFileName.isNotBlank()) {
            return addExtensionIfMissing(trimFileName, extension)
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

    fun addExtensionIfMissing(filename: String, extension: String): String {
        return if (extension.isNotEmpty() && !filename.endsWith(".$extension", ignoreCase = true)) {
            "$filename.$extension"
        } else {
            filename
        }
    }

    fun rename(tempFile: File, finalFile: File): Boolean {
        try {
            if (!tempFile.exists()) return false
            if (finalFile.exists()) {
                finalFile.delete()
                DownloadLog.d { "download666: 文件重命名:${tempFile.absolutePath} finalFile.delete()" }
            }
            if (tempFile.renameTo(finalFile)) {
                tempFile.delete()
                DownloadLog.d { "download666: 文件重命名:${tempFile.absolutePath} renameTo" }
                return true
            }
            Files.copy(tempFile.toPath(), finalFile.toPath())
            tempFile.delete()
            DownloadLog.d { "download666: 文件重命名:${tempFile.absolutePath} Files.copy" }
            return true
        } catch (e: Exception) {
            DownloadLog.e(e) { "download666: 文件重命名:${tempFile.absolutePath} Exception" }
            return false
        }
    }


    /**
     * 删除文件。
     */
    fun deleteFile(fileAbsPath: String?): Boolean {
        try {
            if (fileAbsPath.isNullOrEmpty()) {
                return true
            }
            fileAbsPath.let { tf ->
                val file = File(tf)
                if (!file.exists()) {
                    return true
                }
                val isDelete = Files.deleteIfExists(file.toPath())
                if (isDelete) {
                    DownloadLog.d { "删除文件:(${tf})成功" }
                } else {
                    DownloadLog.d { "删除文件:($tf)失败" }
                }
                return isDelete
            }
        } catch (e: IOException) {
            DownloadLog.e(e) { "删除文件异常:" }
            return false
        }
    }

}

fun String.addApkExtensionIfMissing(): String {
    return FileUtils.addExtensionIfMissing(this, ".apk")
}
