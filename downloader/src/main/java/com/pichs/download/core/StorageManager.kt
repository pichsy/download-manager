package com.pichs.download.core

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.pichs.download.model.DownloadTask
import com.pichs.download.store.TaskRepository
import com.pichs.download.utils.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

internal class StorageManager(
    private val context: Context,
    private val repository: TaskRepository
) {
    
    // 存储配置
    private val config = StorageConfig()
    
    // 空间监控
    private val _availableSpace = MutableStateFlow(0L)
    val availableSpace: StateFlow<Long> = _availableSpace.asStateFlow()
    
    private val _isLowStorage = MutableStateFlow(false)
    val isLowStorage: StateFlow<Boolean> = _isLowStorage.asStateFlow()
    
    // 路径白名单
    private val allowedPaths = setOf(
        context.getExternalFilesDir(null)?.absolutePath,
        context.externalCacheDir?.absolutePath,
        context.cacheDir?.absolutePath,
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
    ).filterNotNull().toSet()
    
    fun startMonitoring() {
        updateStorageStatus()
    }
    
    fun stopMonitoring() {
        // 清理资源
    }
    
    fun updateStorageStatus() {
        val availableSpace = getAvailableSpace()
        _availableSpace.value = availableSpace
        _isLowStorage.value = availableSpace < config.lowStorageThreshold
    }
    
    fun getAvailableSpace(): Long {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            DownloadLogger.logErrorEvent(
                LogLevel.ERROR,
                "Failed to get available space",
                e,
                ErrorContext(
                    taskId = "system",
                    error = DownloadError.FileSystemError,
                    severity = ErrorSeverity.MEDIUM
                )
            )
            0L
        }
    }
    
    fun getTotalSpace(): Long {
        return try {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            stat.blockCountLong * stat.blockSizeLong
        } catch (e: Exception) {
            DownloadLogger.logErrorEvent(
                LogLevel.ERROR,
                "Failed to get total space",
                e,
                ErrorContext(
                    taskId = "system",
                    error = DownloadError.FileSystemError,
                    severity = ErrorSeverity.MEDIUM
                )
            )
            0L
        }
    }
    
    fun isPathAllowed(path: String): Boolean {
        return allowedPaths.any { allowedPath ->
            path.startsWith(allowedPath)
        }
    }
    
    fun getRecommendedPath(): String {
        val externalFilesDir = context.getExternalFilesDir(null)
        return if (externalFilesDir != null && isPathAllowed(externalFilesDir.absolutePath)) {
            externalFilesDir.absolutePath
        } else {
            context.cacheDir.absolutePath
        }
    }
    
    suspend fun cleanupOldTasks() {
        val allTasks = repository.getAll()
        val now = System.currentTimeMillis()
        
        // 按保留策略清理
        val tasksToClean = allTasks.filter { task ->
            val age = now - com.pichs.download.utils.TimeUtils.toMillis(task.updateTime)
            when (task.status) {
                com.pichs.download.model.DownloadStatus.COMPLETED -> {
                    age > config.keepCompletedDays * 24 * 60 * 60 * 1000L
                }
                com.pichs.download.model.DownloadStatus.FAILED,
                com.pichs.download.model.DownloadStatus.CANCELLED -> {
                    age > config.keepFailedDays * 24 * 60 * 60 * 1000L
                }
                else -> false
            }
        }
        
        tasksToClean.forEach { task ->
            try {
                // 内存中的最新状态与数据库快照不一致时跳过，避免误删正在重试的任务
                if (DownloadManager.getTaskImmediate(task.id)?.status != task.status) return@forEach

                // 删除文件；同一路径仍被其他任务引用时保留
                val safeName = FileUtils.sanitizeFileName(task.fileName)
                val shared = FileOwnership.isReferencedByOtherTask(
                    task.id, task.filePath, task.fileName,
                    com.pichs.download.store.InMemoryTaskStore.getAll()
                )
                if (!shared) {
                    File(task.filePath, safeName).takeIf { it.exists() }?.delete()
                    File(task.filePath, "$safeName.part").takeIf { it.exists() }?.delete()
                }
                File(task.filePath, "$safeName.${task.id}.part").takeIf { it.exists() }?.delete()

                // 删除数据库记录与内存状态
                DownloadManager.removeTaskState(task.id)
                
                DownloadLogger.logTaskEvent(
                    LogLevel.INFO,
                    "Cleaned up old task",
                    task,
                    mapOf("reason" to "retention_policy")
                )
            } catch (e: Exception) {
                DownloadLogger.logErrorEvent(
                    LogLevel.ERROR,
                    "Failed to cleanup task",
                    e,
                    ErrorContext(
                        taskId = task.id,
                        error = DownloadError.FileSystemError,
                        severity = ErrorSeverity.LOW
                    )
                )
            }
        }
    }
    
    suspend fun cleanupLowPriorityTasks() {
        if (!_isLowStorage.value) return
        
        val allTasks = repository.getAll()
        val lowPriorityTasks = allTasks.filter { task ->
            task.priority <= 1 && task.status == com.pichs.download.model.DownloadStatus.COMPLETED
        }.sortedBy { it.updateTime } // 删除最旧的
        
        val tasksToDelete = lowPriorityTasks.take(config.maxTasksToDeleteOnLowStorage)
        
        tasksToDelete.forEach { task ->
            try {
                // 只清理仍处于同一状态的已完成任务，避免和用户操作竞争
                if (DownloadManager.getTaskImmediate(task.id)?.status != task.status) return@forEach

                val safeName = FileUtils.sanitizeFileName(task.fileName)
                val shared = FileOwnership.isReferencedByOtherTask(
                    task.id, task.filePath, task.fileName,
                    com.pichs.download.store.InMemoryTaskStore.getAll()
                )
                if (!shared) {
                    File(task.filePath, safeName).takeIf { it.exists() }?.delete()
                    File(task.filePath, "$safeName.part").takeIf { it.exists() }?.delete()
                }
                File(task.filePath, "$safeName.${task.id}.part").takeIf { it.exists() }?.delete()

                DownloadManager.removeTaskState(task.id)
                
                DownloadLogger.logTaskEvent(
                    LogLevel.INFO,
                    "Cleaned up low priority task due to low storage",
                    task
                )
            } catch (e: Exception) {
                DownloadLogger.logErrorEvent(
                    LogLevel.ERROR,
                    "Failed to cleanup low priority task",
                    e,
                    ErrorContext(
                        taskId = task.id,
                        error = DownloadError.FileSystemError,
                        severity = ErrorSeverity.LOW
                    )
                )
            }
        }
    }
    
    fun getStorageInfo(): StorageInfo {
        val totalSpace = getTotalSpace()
        val availableSpace = getAvailableSpace()
        val usedSpace = totalSpace - availableSpace
        
        return StorageInfo(
            totalSpace = totalSpace,
            availableSpace = availableSpace,
            usedSpace = usedSpace,
            isLowStorage = _isLowStorage.value,
            allowedPaths = allowedPaths.toList()
        )
    }
}

data class StorageConfig(
    val lowStorageThreshold: Long = 100 * 1024 * 1024, // 100MB
    val keepCompletedDays: Int = 30,
    val keepFailedDays: Int = 7,
    val maxTasksToDeleteOnLowStorage: Int = 10
)

data class StorageInfo(
    val totalSpace: Long,
    val availableSpace: Long,
    val usedSpace: Long,
    val isLowStorage: Boolean,
    val allowedPaths: List<String>
)
