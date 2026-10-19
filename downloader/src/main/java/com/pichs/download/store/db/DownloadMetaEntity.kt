package com.pichs.download.store.db

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pichs.download.config.Checksum
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 任务级下载元数据：请求头与远端校验器。
 * 进程重启后恢复断点续传时必须还原这些信息，否则鉴权资源会失败，
 * 且无法判断远端文件是否已经变化。
 */
@Keep
@Entity(tableName = "download_meta")
internal data class DownloadMetaEntity(
    @PrimaryKey val taskId: String,
    val headersEncoded: String,
    val eTag: String? = null,
    val lastModified: String? = null,
    val totalSize: Long = -1L,
    val checksumType: String? = null,
    val checksumValue: String? = null,
    val checksumOnFail: String? = null,
)

internal data class DownloadMeta(
    val taskId: String,
    val headers: Map<String, String> = emptyMap(),
    val eTag: String? = null,
    val lastModified: String? = null,
    val totalSize: Long = -1L,
    val checksum: Checksum? = null,
)

internal fun DownloadMetaEntity.toModel(): DownloadMeta = DownloadMeta(
    taskId = taskId,
    headers = HeaderCodec.decode(headersEncoded),
    eTag = eTag,
    lastModified = lastModified,
    totalSize = totalSize,
    checksum = decodeChecksum(checksumType, checksumValue, checksumOnFail),
)

private fun decodeChecksum(type: String?, value: String?, onFail: String?): Checksum? {
    if (type.isNullOrBlank() || value.isNullOrBlank()) return null
    return runCatching {
        Checksum(
            type = Checksum.Type.valueOf(type),
            value = value,
            onFail = onFail?.let { Checksum.OnFail.valueOf(it) } ?: Checksum.OnFail.Ignore,
        )
    }.getOrNull()
}

internal object HeaderCodec {

    fun encode(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        return headers.entries.joinToString("\n") { (key, value) ->
            "${encodePart(key)}:${encodePart(value)}"
        }
    }

    fun decode(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            runCatching {
                val key = decodePart(line.substring(0, separator))
                val value = decodePart(line.substring(separator + 1))
                result[key] = value
            }
        }
        return result
    }

    private fun encodePart(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodePart(value: String): String =
        String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
}
