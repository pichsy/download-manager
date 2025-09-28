package com.pichs.download.core

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
