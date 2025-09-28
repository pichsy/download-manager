package com.pichs.download.core

import android.util.Log
import com.pichs.download.model.DownloadTask
import java.text.SimpleDateFormat
import java.util.*

internal object DownloadLogger {
    
    private const val TAG = "DownloadManager"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    fun logTaskEvent(level: LogLevel, message: String, task: DownloadTask? = null, extra: Map<String, Any> = emptyMap()) {
        val logMessage = buildString {
            append("[${dateFormat.format(Date())}] ")
            append(message)
            task?.let { 
                append(" | TaskId: ${it.id}")
                append(" | Status: ${it.status}")
                append(" | Progress: ${it.progress}%")
                append(" | Speed: ${it.speed}KB/s")
            }
            if (extra.isNotEmpty()) {
                append(" | Extra: ${extra.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
        }
        
        when (level) {
            LogLevel.VERBOSE -> Log.v(TAG, logMessage)
            LogLevel.DEBUG -> Log.d(TAG, logMessage)
            LogLevel.INFO -> Log.i(TAG, logMessage)
            LogLevel.WARN -> Log.w(TAG, logMessage)
            LogLevel.ERROR -> Log.e(TAG, logMessage)
        }
    }
    
    fun logNetworkEvent(level: LogLevel, message: String, networkType: NetworkType, extra: Map<String, Any> = emptyMap()) {
        val logMessage = buildString {
            append("[${dateFormat.format(Date())}] ")
            append(message)
            append(" | Network: $networkType")
            if (extra.isNotEmpty()) {
                append(" | Extra: ${extra.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
        }
        
        when (level) {
            LogLevel.VERBOSE -> Log.v(TAG, logMessage)
            LogLevel.DEBUG -> Log.d(TAG, logMessage)
            LogLevel.INFO -> Log.i(TAG, logMessage)
            LogLevel.WARN -> Log.w(TAG, logMessage)
            LogLevel.ERROR -> Log.e(TAG, logMessage)
        }
    }
    
    fun logErrorEvent(level: LogLevel, message: String, error: Throwable, context: ErrorContext) {
        val logMessage = buildString {
            append("[${dateFormat.format(Date())}] ")
            append(message)
            append(" | TaskId: ${context.taskId}")
            append(" | Error: ${error.javaClass.simpleName}")
            append(" | Severity: ${context.severity}")
            append(" | RetryCount: ${context.retryCount}")
            append(" | Network: ${context.networkType}")
            append(" | Battery: ${context.batteryLevel}%")
            if (context.extra.isNotEmpty()) {
                append(" | Extra: ${context.extra.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
        }
        
        when (level) {
            LogLevel.VERBOSE -> Log.v(TAG, logMessage)
            LogLevel.DEBUG -> Log.d(TAG, logMessage)
            LogLevel.INFO -> Log.i(TAG, logMessage)
            LogLevel.WARN -> Log.w(TAG, logMessage)
            LogLevel.ERROR -> Log.e(TAG, logMessage)
        }
    }
    
    fun logChunkEvent(level: LogLevel, message: String, taskId: String, chunkIndex: Int, extra: Map<String, Any> = emptyMap()) {
        val logMessage = buildString {
            append("[${dateFormat.format(Date())}] ")
            append(message)
            append(" | TaskId: $taskId")
            append(" | ChunkIndex: $chunkIndex")
            if (extra.isNotEmpty()) {
                append(" | Extra: ${extra.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            }
        }
        
        when (level) {
            LogLevel.VERBOSE -> Log.v(TAG, logMessage)
            LogLevel.DEBUG -> Log.d(TAG, logMessage)
            LogLevel.INFO -> Log.i(TAG, logMessage)
            LogLevel.WARN -> Log.w(TAG, logMessage)
            LogLevel.ERROR -> Log.e(TAG, logMessage)
        }
    }
}

enum class LogLevel {
    VERBOSE, DEBUG, INFO, WARN, ERROR
}
