package com.pichs.download.core

import com.pichs.download.model.DownloadTask
import com.pichs.download.model.DownloadStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 多线程下载进度计算器
 * 负责聚合多个分片的进度和速度，计算总体进度
 */
class ProgressCalculator {
    
    // 每个任务的进度跟踪数据
    private data class TaskProgress(
        var lastUpdateTime: Long = System.currentTimeMillis(),
        var lastTotalBytes: Long = 0,
        var lastSpeed: Long = 0,
        var speedHistory: MutableList<Long> = mutableListOf(),
        var lastNotifyTime: Long = 0
    )
    
    private val taskProgressMap = ConcurrentHashMap<String, TaskProgress>()
    private val mutex = Mutex()
    
    /**
     * 计算并更新任务进度
     * @param task 当前任务
     * @param chunks 所有分片列表
     * @param totalSize 总文件大小
     * @param minUpdateInterval 最小更新间隔（毫秒），默认500ms
     * @return 是否需要更新UI，以及更新后的任务对象
     */
    suspend fun calculateProgress(
        task: DownloadTask,
        chunks: List<com.pichs.download.model.DownloadChunk>,
        totalSize: Long,
        minUpdateInterval: Long = 500L
    ): Pair<Boolean, DownloadTask> = mutex.withLock {
        
        val now = System.currentTimeMillis()
        val taskProgress = taskProgressMap.getOrPut(task.id) { TaskProgress() }
        
        // 计算总下载字节数
        val totalDownloaded = chunks.sumOf { it.downloaded }
        
        // 计算进度百分比
        val progress = if (totalSize > 0) {
            ((totalDownloaded * 100) / totalSize).toInt().coerceIn(0, 100)
        } else {
            0
        }
        
        // 计算下载速度
        val deltaT = now - taskProgress.lastUpdateTime
        val deltaB = totalDownloaded - taskProgress.lastTotalBytes
        val currentSpeed = if (deltaT > 0) {
            (deltaB * 1000 / deltaT).coerceAtLeast(0)
        } else {
            0
        }
        
        // 平滑速度计算（使用移动平均）
        val smoothedSpeed = calculateSmoothedSpeed(taskProgress, currentSpeed)
        
        // 检查是否需要更新：时间间隔 + 进度变化
        val timeElapsed = (now - taskProgress.lastNotifyTime) >= minUpdateInterval
        val progressChanged = progress != task.progress
        val speedChanged = kotlin.math.abs(smoothedSpeed - task.speed) > 1024 // 速度变化超过1KB/s
        
        val shouldUpdate = timeElapsed && (progressChanged || speedChanged)
        
        if (shouldUpdate) {
            // 更新跟踪数据
            taskProgress.lastUpdateTime = now
            taskProgress.lastTotalBytes = totalDownloaded
            taskProgress.lastSpeed = smoothedSpeed
            taskProgress.lastNotifyTime = now
            
            // 创建更新后的任务对象
            val updatedTask = task.copy(
                status = DownloadStatus.DOWNLOADING,
                progress = progress,
                totalSize = totalSize,
                currentSize = totalDownloaded,
                speed = smoothedSpeed,
                updateTime = now
            )
            
            return Pair(true, updatedTask)
        }
        
        return Pair(false, task)
    }
    
    /**
     * 计算平滑的下载速度（移动平均）
     */
    private fun calculateSmoothedSpeed(taskProgress: TaskProgress, currentSpeed: Long): Long {
        // 添加到历史记录
        taskProgress.speedHistory.add(currentSpeed)
        
        // 保持最近10个速度记录
        if (taskProgress.speedHistory.size > 10) {
            taskProgress.speedHistory.removeAt(0)
        }
        
        // 计算平均值
        return if (taskProgress.speedHistory.isNotEmpty()) {
            taskProgress.speedHistory.average().toLong()
        } else {
            currentSpeed
        }
    }
    
    /**
     * 获取任务的当前速度
     */
    fun getCurrentSpeed(taskId: String): Long {
        return taskProgressMap[taskId]?.lastSpeed ?: 0
    }
    
    /**
     * 获取任务的当前进度
     */
    fun getCurrentProgress(taskId: String, chunks: List<com.pichs.download.model.DownloadChunk>, totalSize: Long): Int {
        val totalDownloaded = chunks.sumOf { it.downloaded }
        return if (totalSize > 0) {
            ((totalDownloaded * 100) / totalSize).toInt().coerceIn(0, 100)
        } else {
            0
        }
    }
    
    /**
     * 清理任务进度数据
     */
    fun clearTaskProgress(taskId: String) {
        taskProgressMap.remove(taskId)
    }
    
    /**
     * 清理所有进度数据
     */
    fun clearAllProgress() {
        taskProgressMap.clear()
    }
    
    /**
     * 获取任务完成时的最终进度信息
     */
    fun getFinalProgress(
        task: DownloadTask,
        chunks: List<com.pichs.download.model.DownloadChunk>,
        totalSize: Long
    ): DownloadTask {
        val totalDownloaded = chunks.sumOf { it.downloaded }
        val progress = if (totalSize > 0) {
            ((totalDownloaded * 100) / totalSize).toInt().coerceIn(0, 100)
        } else {
            100
        }
        
        return task.copy(
            status = DownloadStatus.COMPLETED,
            progress = 100,
            totalSize = totalSize,
            currentSize = totalDownloaded,
            speed = 0,
            updateTime = System.currentTimeMillis()
        )
    }
}
