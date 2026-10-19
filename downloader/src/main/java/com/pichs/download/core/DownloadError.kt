package com.pichs.download.core

/**
 * 文件内容与调用方提供的校验值不一致。
 * 不继承 IOException，避免被网络/存储错误分类误判为可恢复暂停。
 */
internal class ChecksumMismatchException(message: String) : Exception(message)

sealed class DownloadError : Exception() {
    object NetworkError : DownloadError()
    object FileSystemError : DownloadError()
    object ValidationError : DownloadError()
    object BusinessError : DownloadError()
    object CancelledError : DownloadError()
    
    data class HttpError(val code: Int, override val message: String) : DownloadError()
    data class StorageError(override val message: String) : DownloadError()
    data class ChunkError(val chunkIndex: Int, override val message: String) : DownloadError()
    data class RetryError(val attemptCount: Int, val lastError: Throwable) : DownloadError()
}

enum class ErrorSeverity {
    LOW,      // 可重试的错误
    MEDIUM,   // 需要用户干预的错误
    HIGH,     // 严重错误，需要停止下载
    CRITICAL  // 系统级错误
}

data class ErrorContext(
    val taskId: String,
    val error: DownloadError,
    val severity: ErrorSeverity,
    val timestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val networkType: NetworkType = NetworkType.UNKNOWN,
    val batteryLevel: Int = 100,
    val extra: Map<String, Any> = emptyMap()
)
