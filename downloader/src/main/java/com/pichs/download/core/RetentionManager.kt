package com.pichs.download.core

import com.pichs.download.config.RetentionConfig
import com.pichs.download.config.RetentionStats
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.store.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class RetentionManager(
    private val repository: TaskRepository,
    private val storageManager: StorageManager
) {
    
    // ✅ 改为 var，支持外部更新配置
    internal var config = RetentionConfig()
    
    suspend fun executeRetentionPolicy() {
        withContext(Dispatchers.IO) {
            try {
                // 1. 按时间清理
                cleanupByTime()
                
                // 2. 按数量清理
                cleanupByCount()
                
                // 3. 按存储空间清理
                if (storageManager.isLowStorage.value) {
                    cleanupByStorage()
                }
                
                DownloadLogger.logTaskEvent(
                    LogLevel.INFO,
                    "Retention policy executed successfully",
                    extra = mapOf("timestamp" to System.currentTimeMillis())
                )
            } catch (e: Exception) {
                DownloadLogger.logErrorEvent(
                    LogLevel.ERROR,
                    "Failed to execute retention policy",
                    e,
                    ErrorContext(
                        taskId = "system",
                        error = DownloadError.FileSystemError,
                        severity = ErrorSeverity.MEDIUM
                    )
                )
            }
        }
    }
    
    private suspend fun cleanupByTime() {
        val allTasks = repository.getAll()
        val now = System.currentTimeMillis()
        val protectionPeriodMs = config.protectionPeriodHours * 60 * 60 * 1000L
        
        val tasksToClean = allTasks.filter { task ->
            // ✅ 先检查是否在保护期内
            val age = now - task.updateTime
            if (age < protectionPeriodMs) {
                return@filter false  // 保护期内，不清理
            }
            
            when (task.status) {
                DownloadStatus.COMPLETED -> {
                    age > config.keepCompletedDays * 24 * 60 * 60 * 1000L
                }
                DownloadStatus.FAILED -> {
                    age > config.keepFailedDays * 24 * 60 * 60 * 1000L
                }
                DownloadStatus.CANCELLED -> {
                    age > config.keepCancelledDays * 24 * 60 * 60 * 1000L
                }
                else -> false
            }
        }
        
        tasksToClean.forEach { task ->
            deleteTaskAndFile(task, "time_based_cleanup")
        }
    }
    
    private suspend fun cleanupByCount() {
        val allTasks = repository.getAll()
        val now = System.currentTimeMillis()
        val protectionPeriodMs = config.protectionPeriodHours * 60 * 60 * 1000L
        
        // ✅ 先过滤掉保护期内的任务，再按状态分组
        val completedTasks = allTasks
            .filter { it.status == DownloadStatus.COMPLETED }
            .filter { now - it.updateTime >= protectionPeriodMs }  // 排除保护期
            .sortedByDescending { it.updateTime }
        
        val failedTasks = allTasks
            .filter { it.status == DownloadStatus.FAILED }
            .filter { now - it.updateTime >= protectionPeriodMs }  // 排除保护期
            .sortedByDescending { it.updateTime }
        
        // 保留最近N个，删除其余的
        val completedToDelete = completedTasks.drop(config.keepLatestCompleted)
        val failedToDelete = failedTasks.drop(config.keepLatestFailed)
        
        (completedToDelete + failedToDelete).forEach { task ->
            deleteTaskAndFile(task, "count_based_cleanup")
        }
    }
    
    private suspend fun cleanupByStorage() {
        val allTasks = repository.getAll()
        val now = System.currentTimeMillis()
        val protectionPeriodMs = config.protectionPeriodHours * 60 * 60 * 1000L
        
        // ✅ 按优先级和大小排序，优先删除低优先级、大文件，但排除保护期内的任务
        val tasksToDelete = allTasks
            .filter { it.status == DownloadStatus.COMPLETED }
            .filter { now - it.updateTime >= protectionPeriodMs }  // 排除保护期
            .sortedWith(
                compareBy<DownloadTask> { it.priority } // 低优先级在前
                    .thenByDescending { it.totalSize } // 大文件在前
                    .thenBy { it.updateTime } // 旧文件在前
            )
            .take(config.maxTasksToDeleteOnLowStorage)
        
        tasksToDelete.forEach { task ->
            deleteTaskAndFile(task, "storage_based_cleanup")
        }
    }
    
    private suspend fun deleteTaskAndFile(task: DownloadTask, reason: String) {
        try {
            // 删除文件
            val file = File(task.filePath, task.fileName)
            if (file.exists()) {
                file.delete()
            }
            
            // 删除临时文件
            val tempFile = File(task.filePath, "${task.fileName}.part")
            if (tempFile.exists()) {
                tempFile.delete()
            }
            
            // 删除数据库记录
            repository.delete(task.id)
            
            DownloadLogger.logTaskEvent(
                LogLevel.INFO,
                "Task deleted by retention policy",
                task,
                mapOf("reason" to reason)
            )
            
            // 发送删除事件
            DownloadEventBus.emitTaskEvent(TaskEvent.TaskRemoved(task.id))
            
        } catch (e: Exception) {
            DownloadLogger.logErrorEvent(
                LogLevel.ERROR,
                "Failed to delete task during retention cleanup",
                e,
                ErrorContext(
                    taskId = task.id,
                    error = DownloadError.FileSystemError,
                    severity = ErrorSeverity.LOW
                )
            )
        }
    }
    
    suspend fun getRetentionStats(): RetentionStats {
        val allTasks = repository.getAll()
        val completedTasks = allTasks.filter { it.status == DownloadStatus.COMPLETED }
        val failedTasks = allTasks.filter { it.status == DownloadStatus.FAILED }
        val cancelledTasks = allTasks.filter { it.status == DownloadStatus.CANCELLED }
        
        return RetentionStats(
            totalTasks = allTasks.size,
            completedTasks = completedTasks.size,
            failedTasks = failedTasks.size,
            cancelledTasks = cancelledTasks.size,
            totalSize = allTasks.sumOf { it.totalSize },
            oldestTask = allTasks.minByOrNull { it.createTime }?.createTime ?: 0L
        )
    }
}
