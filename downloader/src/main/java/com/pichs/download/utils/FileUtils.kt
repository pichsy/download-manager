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
     * 优先级：用户传入的文件名(带后缀) > URL中的后缀 > Content-Type映射
     */
    fun generateFilename(fileName: String?, url: String, contentType: String?): String {
        val trimFileName = fileName?.trim()
        
        // 1. 用户传入的文件名带后缀，直接使用
        if (!trimFileName.isNullOrEmpty() && trimFileName.contains('.')) {
            return sanitizeFilename(trimFileName)
        }

        // 2. 从 URL 中解析后缀
        val decodedUrl = try {
            URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            url
        }
        val cleanUrl = decodedUrl.substringBefore('?').substringBefore('#')
        val lastPart = cleanUrl.substringAfterLast('/')
        val urlExtension = lastPart.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: ""
        
        // 3. 从 Content-Type 解析后缀（作为最后选择）
        val contentTypeExtension = getExtensionFromContentType(contentType) ?: ""
        
        // 优先使用 URL 后缀，解析不到才用 Content-Type
        val extension = urlExtension.ifEmpty { contentTypeExtension }

        // 生成最终文件名
        return if (!trimFileName.isNullOrEmpty()) {
            // 用户传入了文件名（不带后缀），补上后缀
            if (extension.isNotEmpty()) "$trimFileName.$extension" else trimFileName
        } else if (lastPart.isNotEmpty()) {
            // 用 URL 中的文件名
            val sanitizedName = sanitizeFilename(lastPart.substringBefore('?'))
            if (sanitizedName.length > MAX_FILENAME_LENGTH) {
                truncateFilename(sanitizedName.substringBeforeLast('.', sanitizedName), extension)
            } else if (sanitizedName.contains('.')) {
                sanitizedName
            } else {
                if (extension.isNotEmpty()) "$sanitizedName.$extension" else sanitizedName
            }
        } else {
            // 兜底：用 MD5
            "${MD5Utils.md5(url)}${if (extension.isNotEmpty()) ".$extension" else ""}"
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
            }
            if (tempFile.renameTo(finalFile)) {
                tempFile.delete()
                return true
            }
            Files.copy(tempFile.toPath(), finalFile.toPath())
            tempFile.delete()
            return true
        } catch (e: Exception) {
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
                    DownloadLog.d("TAG", "删除文件:(${tf})成功")
                } else {
                    DownloadLog.d("TAG", "删除文件:(${tf})失败")
                }
                return isDelete
            }
        } catch (e: IOException) {
            return false
        }
    }

}

fun String.addApkExtensionIfMissing(): String {
    return FileUtils.addExtensionIfMissing(this, "apk")
}
