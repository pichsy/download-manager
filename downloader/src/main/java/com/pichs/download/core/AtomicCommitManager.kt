package com.pichs.download.core

import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.store.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class AtomicCommitManager(private val repository: TaskRepository) {
    
    suspend fun commitTaskCompletion(task: DownloadTask, finalFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 验证文件完整性
                if (!validateFileIntegrity(task, finalFile)) {
                    DownloadLogger.logTaskEvent(
                        LogLevel.ERROR, 
                        "File integrity validation failed", 
                        task,
                        mapOf("fileSize" to finalFile.length(), "expectedSize" to task.totalSize)
                    )
                    return@withContext false
                }
                
                // 2. 原子性更新：先更新数据库状态
                val completedTask = task.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    currentSize = task.totalSize,
                    speed = 0,
                    updateTime = com.pichs.download.utils.TimeUtils.currentMicros()
                )
                
                repository.save(completedTask)
                
                // 3. 验证数据库更新成功
                val savedTask = repository.getById(task.id)
                if (savedTask?.status != DownloadStatus.COMPLETED) {
                    DownloadLogger.logTaskEvent(
                        LogLevel.ERROR,
                        "Database update verification failed",
                        task
                    )
                    return@withContext false
                }
                
                // 4. 清理临时文件
                cleanupTempFiles(task)
                
                // 5. 发送完成事件
                DownloadEventBus.emitTaskEvent(TaskEvent.TaskCompleted(completedTask, finalFile))
                
                DownloadLogger.logTaskEvent(
                    LogLevel.INFO,
                    "Task completed successfully",
                    completedTask,
                    mapOf("filePath" to finalFile.absolutePath)
                )
                
                true
            } catch (e: Exception) {
                DownloadLogger.logErrorEvent(
                    LogLevel.ERROR,
                    "Atomic commit failed",
                    e,
                    ErrorContext(
                        taskId = task.id,
                        error = DownloadError.FileSystemError,
                        severity = ErrorSeverity.HIGH,
                        extra = mapOf("filePath" to finalFile.absolutePath)
                    )
                )
                false
            }
        }
    }
    
    suspend fun commitTaskFailure(task: DownloadTask, error: Throwable): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val failedTask = task.copy(
                    status = DownloadStatus.FAILED,
                    updateTime = com.pichs.download.utils.TimeUtils.currentMicros()
                )
                
                repository.save(failedTask)
                
                // 清理临时文件
                cleanupTempFiles(task)
                
                // 发送错误事件
                val errorContext = ErrorContext(
                    taskId = task.id,
                    error = when (error) {
                        is DownloadError -> error
                        else -> DownloadError.NetworkError
                    },
                    severity = ErrorSeverity.MEDIUM
                )
                
                DownloadEventBus.emitErrorEvent(ErrorEvent.TaskError(errorContext))
                
                DownloadLogger.logErrorEvent(
                    LogLevel.ERROR,
                    "Task failed",
                    error,
                    errorContext
                )
                
                true
            } catch (e: Exception) {
                DownloadLogger.logErrorEvent(
                    LogLevel.ERROR,
                    "Failed to commit task failure",
                    e,
                    ErrorContext(
                        taskId = task.id,
                        error = DownloadError.FileSystemError,
                        severity = ErrorSeverity.CRITICAL
                    )
                )
                false
            }
        }
    }
    
    suspend fun commitTaskCancellation(task: DownloadTask): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val cancelledTask = task.copy(
                    status = DownloadStatus.CANCELLED,
                    updateTime = com.pichs.download.utils.TimeUtils.currentMicros()
                )
                
                repository.save(cancelledTask)
                
                // 清理临时文件
                cleanupTempFiles(task)
                
                // 发送取消事件
                DownloadEventBus.emitTaskEvent(TaskEvent.TaskCancelled(cancelledTask))
                
                DownloadLogger.logTaskEvent(
                    LogLevel.INFO,
                    "Task cancelled",
                    cancelledTask
                )
                
                true
            } catch (e: Exception) {
                DownloadLogger.logErrorEvent(
                    LogLevel.ERROR,
                    "Failed to commit task cancellation",
                    e,
                    ErrorContext(
                        taskId = task.id,
                        error = DownloadError.FileSystemError,
                        severity = ErrorSeverity.MEDIUM
                    )
                )
                false
            }
        }
    }
    
    private fun validateFileIntegrity(task: DownloadTask, file: File): Boolean {
        if (!file.exists()) return false
        if (file.length() != task.totalSize) return false
        if (task.totalSize <= 0) return false
        
        // 这里可以添加更多验证逻辑，如MD5校验等
        return true
    }
    
    private fun cleanupTempFiles(task: DownloadTask) {
        try {
            val tempFile = File(task.filePath, "${task.fileName}.part")
            if (tempFile.exists()) {
                tempFile.delete()
            }
        } catch (e: Exception) {
            DownloadLogger.logTaskEvent(
                LogLevel.WARN,
                "Failed to cleanup temp files",
                task,
//                mapOf("error" to e.message)
            )
        }
    }
}
