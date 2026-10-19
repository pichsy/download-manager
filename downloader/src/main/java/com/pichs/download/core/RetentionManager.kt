package com.pichs.download.core

import com.pichs.download.config.RetentionConfig
import com.pichs.download.config.RetentionStats
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.store.InMemoryTaskStore
import com.pichs.download.store.TaskRepository
import com.pichs.download.utils.FileUtils
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
            // ✅ 先检查是否在保护期内（updateTime 为微秒，需转毫秒）
            val age = now - com.pichs.download.utils.TimeUtils.toMillis(task.updateTime)
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
            .filter { now - com.pichs.download.utils.TimeUtils.toMillis(it.updateTime) >= protectionPeriodMs }
            .sortedByDescending { it.updateTime }
        
        val failedTasks = allTasks
            .filter { it.status == DownloadStatus.FAILED }
            .filter { now - com.pichs.download.utils.TimeUtils.toMillis(it.updateTime) >= protectionPeriodMs }
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
            .filter { now - com.pichs.download.utils.TimeUtils.toMillis(it.updateTime) >= protectionPeriodMs }
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
            // 内存状态已变化（例如用户刚恢复该任务）时跳过，避免误删
            val live = DownloadManager.getTaskImmediate(task.id)
            if (live != null && live.status != task.status) return

            // 删除文件（清洗文件名，防止历史脏数据造成路径穿越）
            val safeName = FileUtils.sanitizeFileName(task.fileName)
            val shared = FileOwnership.isReferencedByOtherTask(
                task.id, task.filePath, task.fileName, InMemoryTaskStore.getAll()
            )
            if (!shared) {
                File(task.filePath, safeName).takeIf { it.exists() }?.delete()
                // 删除临时文件（旧命名与带任务 ID 的新命名）
                File(task.filePath, "$safeName.part").takeIf { it.exists() }?.delete()
            }
            File(task.filePath, "$safeName.${task.id}.part").takeIf { it.exists() }?.delete()
            
            // 删除数据库记录与内存状态（串行持久化队列保证顺序）
            DownloadManager.removeTaskState(task.id)
            
            DownloadLogger.logTaskEvent(
                LogLevel.INFO,
                "Task deleted by retention policy",
                task,
                mapOf("reason" to reason)
            )
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
            oldestTask = allTasks.minByOrNull { it.createTime }?.createTime
                ?.let { com.pichs.download.utils.TimeUtils.toMillis(it) } ?: 0L
        )
    }
}
