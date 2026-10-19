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
    /**
     * 统一路径比较口径。解析失败时退回绝对路径，保证调用方永远拿到可比较的字符串。
     */
    fun canonicalPath(path: String): String {
        if (path.isBlank()) return path
        val file = File(path)
        return try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }
    }

    private const val MAX_FILENAME_LENGTH = 32
    private const val MAX_SAFE_FILENAME_LENGTH = 128
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
     * 与 [checkAndCreateFileSafe] 相同，但保留底层异常。
     * 存储空间不足需要依赖 IOException 的原始信息做分类，不能在这里吞掉。
     */
    suspend fun checkAndCreateFileSafeOrThrow(file: File) {
        withContext(Dispatchers.IO) {
            if (file.exists() && file.isDirectory) {
                checkAndDeleteFile(file)
            }
            if (!file.exists()) {
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                file.createNewFile()
            }
            if (!file.exists()) {
                throw java.io.IOException("无法创建文件: ${file.absolutePath}")
            }
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
            return sanitizeFileName(trimFileName)
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
            sanitizeFileName(if (extension.isNotEmpty()) "$trimFileName.$extension" else trimFileName)
        } else if (lastPart.isNotEmpty()) {
            // 用 URL 中的文件名
            val sanitizedName = sanitizeFileName(lastPart.substringBefore('?'))
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

    /**
     * 解析任务最终使用的文件名。
     * 优先级：自定义解析器 > 调用方显式指定 > URL 末段。
     * 解析器异常时回退到默认规则，不影响任务创建。
     */
    fun resolveFileName(
        url: String,
        fileName: String?,
        resolver: com.pichs.download.config.FileNameResolver?
    ): String {
        val custom = runCatching { resolver?.resolve(url, fileName) }.getOrNull()?.trim()
        val raw = if (!custom.isNullOrEmpty()) {
            custom
        } else {
            fileName?.takeIf { it.isNotBlank() }
                ?: url.substringAfterLast('/').substringBefore('?')
        }
        return sanitizeFileName(raw)
    }

    /**
     * 清洗文件名，去掉路径分隔符与非法字符，并阻止 `.` / `..` 路径穿越。
     * 调用方（包括 fileName 直接指定的场景）都必须先经过这里。
     */
    fun sanitizeFileName(filename: String): String {
        val cleaned = filename.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return "download"
        if (cleaned.length <= MAX_SAFE_FILENAME_LENGTH) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val name = if (dot > 0) cleaned.substring(0, dot) else cleaned
        val ext = if (dot > 0) cleaned.substring(dot) else ""
        return name.take(MAX_SAFE_FILENAME_LENGTH) + ext
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
        if (!tempFile.exists()) return false
        try {
            Files.move(
                tempFile.toPath(),
                finalFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            return true
        } catch (_: Exception) {
            // 跨文件系统等场景退化为复制
        }
        return try {
            Files.copy(
                tempFile.toPath(),
                finalFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            Files.deleteIfExists(tempFile.toPath())
            true
        } catch (e: Exception) {
            // 复制失败时清掉可能写了一半的目标文件，避免留下损坏的“完整文件”
            runCatching { Files.deleteIfExists(finalFile.toPath()) }
            false
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
