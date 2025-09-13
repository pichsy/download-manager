package com.pichs.download.core

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
    
    private val config = RetentionConfig()
    
    suspend fun executeRetentionPolicy() {
        withContext(Dispatchers.IO) {
            try {
                // 1. 按时间清理
                cleanupByTime()
                
                // 2. 按数量清理
                cleanupByCount()
                
                // 3. 按标签清理
                cleanupByTag()
                
                // 4. 按存储空间清理
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
        
        val tasksToClean = allTasks.filter { task ->
            when (task.status) {
                DownloadStatus.COMPLETED -> {
                    val age = now - task.updateTime
                    age > config.keepCompletedDays * 24 * 60 * 60 * 1000L
                }
                DownloadStatus.FAILED -> {
                    val age = now - task.updateTime
                    age > config.keepFailedDays * 24 * 60 * 60 * 1000L
                }
                DownloadStatus.CANCELLED -> {
                    val age = now - task.updateTime
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
        
        // 按状态分组
        val completedTasks = allTasks.filter { it.status == DownloadStatus.COMPLETED }
            .sortedByDescending { it.updateTime }
        
        val failedTasks = allTasks.filter { it.status == DownloadStatus.FAILED }
            .sortedByDescending { it.updateTime }
        
        // 保留最近N个，删除其余的
        val completedToDelete = completedTasks.drop(config.keepLatestCompleted)
        val failedToDelete = failedTasks.drop(config.keepLatestFailed)
        
        (completedToDelete + failedToDelete).forEach { task ->
            deleteTaskAndFile(task, "count_based_cleanup")
        }
    }
    
    private suspend fun cleanupByTag() {
        val allTasks = repository.getAll()
        
        // 按标签分组
        val tasksByTag = allTasks.groupBy { task ->
            task.extras?.let { extras ->
                // 从extras JSON中提取tag
                try {
                    val json = com.google.gson.Gson().fromJson<Map<String, Any>>(extras, Map::class.java)
                    json["tag"] as? String
                } catch (e: Exception) {
                    null
                }
            } ?: "default"
        }
        
        tasksByTag.forEach { (tag, tasks) ->
            val tagConfig = config.tagConfigs[tag] ?: config.defaultTagConfig
            val tasksToKeep = tasks.sortedByDescending { it.updateTime }.take(tagConfig.maxTasks)
            val tasksToDelete = tasks - tasksToKeep.toSet()
            
            tasksToDelete.forEach { task ->
                deleteTaskAndFile(task, "tag_based_cleanup")
            }
        }
    }
    
    private suspend fun cleanupByStorage() {
        val allTasks = repository.getAll()
        
        // 按优先级和大小排序，优先删除低优先级、大文件
        val tasksToDelete = allTasks
            .filter { it.status == DownloadStatus.COMPLETED }
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

data class RetentionConfig(
    val keepCompletedDays: Int = 30,
    val keepFailedDays: Int = 7,
    val keepCancelledDays: Int = 3,
    val keepLatestCompleted: Int = 100,
    val keepLatestFailed: Int = 20,
    val maxTasksToDeleteOnLowStorage: Int = 10,
    val tagConfigs: Map<String, TagConfig> = mapOf(
        "critical" to TagConfig(maxTasks = 50, keepDays = 90),
        "important" to TagConfig(maxTasks = 30, keepDays = 60),
        "normal" to TagConfig(maxTasks = 20, keepDays = 30),
        "background" to TagConfig(maxTasks = 10, keepDays = 7)
    ),
    val defaultTagConfig: TagConfig = TagConfig(maxTasks = 20, keepDays = 30)
)

data class TagConfig(
    val maxTasks: Int,
    val keepDays: Int
)

data class RetentionStats(
    val totalTasks: Int,
    val completedTasks: Int,
    val failedTasks: Int,
    val cancelledTasks: Int,
    val totalSize: Long,
    val oldestTask: Long
)
