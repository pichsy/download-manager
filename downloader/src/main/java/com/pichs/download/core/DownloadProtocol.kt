package com.pichs.download.core

import java.io.IOException

/**
 * 服务端返回了非预期状态码，任务应直接失败，不能把错误页写入文件。
 */
internal class HttpStatusException(
    val code: Int,
    message: String = "HTTP $code"
) : IOException(message) {
    val retryable: Boolean
        get() = code == 408 || code == 425 || code == 429 || code in 500..599
}

/**
 * 下载数据与服务端声明不一致（长度不符、Content-Range 不匹配等）。
 */
internal class FileIntegrityException(message: String) : IOException(message)

/**
 * 服务端忽略了 Range 请求，需要退回单流全量下载。
 */
internal class FullDownloadFallbackException : IOException("range fallback required")

/**
 * HEAD 探测到的大小与分段响应的 Content-Range 总大小不一致（CDN 多副本或文件已更新），
 * 需要以分段响应为准重建分片后重新下载。
 */
internal class RemoteSizeChangedException(
    val actualTotal: Long,
    val actualETag: String?
) : IOException("remote size changed: actual=$actualTotal")

internal data class ContentRange(
    val start: Long,
    val end: Long,
    val total: Long
)

/**
 * 解析 `Content-Range: bytes start-end/total`。
 * total 为 `*` 时返回 -1。
 */
internal fun parseContentRange(raw: String?): ContentRange? {
    if (raw.isNullOrBlank()) return null
    val match = CONTENT_RANGE_REGEX.find(raw.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (end < start) return null
    val totalRaw = match.groupValues[3]
    val total = if (totalRaw == "*") -1L else totalRaw.toLongOrNull() ?: return null
    return ContentRange(start, end, total)
}

private val CONTENT_RANGE_REGEX =
    Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)

private val UNSATISFIED_RANGE_REGEX =
    Regex("""^bytes\s+\*/(\d+)$""", RegexOption.IGNORE_CASE)

/**
 * 解析 `Content-Range: bytes *\/N`（416 响应），N 表示远端文件总长度。
 */
internal fun parseUnsatisfiedRangeTotal(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return UNSATISFIED_RANGE_REGEX.find(raw.trim())?.groupValues?.get(1)?.toLongOrNull()
}
