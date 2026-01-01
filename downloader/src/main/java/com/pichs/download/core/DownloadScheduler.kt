package com.pichs.download.core

import android.content.Context
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.ConcurrentHashMap

internal class DownloadScheduler(
    private val context: Context,
    private val engine: DownloadEngine,
    private val dispatcher: AdvancedDownloadQueueDispatcher
) {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkMonitor = NetworkMonitor(context)
    private val runningJobs = ConcurrentHashMap<String, Job>()
    
    private var config = com.pichs.download.config.DownloadConfig()
    
    fun start() {
        networkMonitor.startMonitoring()
        
        // 监听网络状态变化
        scope.launch {
            networkMonitor.networkType.collect { networkType ->
                onNetworkChanged(networkType)
            }
        }
        
        // 监听电池状态变化
        scope.launch {
            networkMonitor.isLowBattery.collect { isLowBattery ->
                onBatteryChanged(isLowBattery)
            }
        }
        
        // 不需要轮询循环：调度由事件驱动
        // - 任务完成/失败/暂停 → updateTaskInternal() 调用 scheduleNext()
        // - 任务恢复 → resume() 调用 scheduleNext()
        // - 新任务入队 → enqueue() 调用 scheduleNext()
        // - 网络/电池变化 → 上面的 collect 处理
    }
    
    fun stop() {
        networkMonitor.stopMonitoring()
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
    }
    
    fun enqueue(task: DownloadTask) {
        dispatcher.enqueue(task)
    }
    
    fun remove(taskId: String) {
        dispatcher.remove(taskId)
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
    }
    
    fun pause(taskId: String) {
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
        // 引擎暂停逻辑
    }
    
    fun resume(taskId: String) {
        val task = dispatcher.getRunningTasks().find { it.id == taskId }
        if (task != null) {
            startTask(task)
        }
    }
    
    // 公开调度方法供 Manager 调用
    // 使用 UNDISPATCHED 确保立即开始执行，不等待调度器
    fun trySchedule() {
        com.pichs.download.utils.DownloadLog.d("DownloadScheduler", "trySchedule() called")
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            scheduleNextInternal()
        }
    }

    private suspend fun scheduleNextInternal() {
        val currentLimit = dispatcher.getCurrentConcurrencyLimit()
        val runningCount = dispatcher.getRunningTasks().size
        val waitingCount = dispatcher.getWaitingTasks().size
        
        com.pichs.download.utils.DownloadLog.d("DownloadScheduler", "scheduleNextInternal: limit=$currentLimit, running=$runningCount, waiting=$waitingCount")
        
        if (runningCount < currentLimit) {
            val nextTask = dispatcher.dequeueWithPreemption()
            if (nextTask != null) {
                com.pichs.download.utils.DownloadLog.d("DownloadScheduler", "Starting task: ${nextTask.fileName}")
                startTask(nextTask)
            } else {
                com.pichs.download.utils.DownloadLog.d("DownloadScheduler", "No task to dequeue")
            }
        } else {
            com.pichs.download.utils.DownloadLog.d("DownloadScheduler", "Concurrent limit reached, not scheduling")
        }
    }
    
    private fun startTask(task: DownloadTask) {
        val job = scope.launch {
            try {
                engine.start(task)
            } catch (e: Exception) {
                // 处理启动失败
                handleTaskError(task, e)
            }
        }
        runningJobs[task.id] = job
        
        job.invokeOnCompletion { cause ->
            runningJobs.remove(task.id)
            if (cause != null) {
                handleTaskError(task, cause)
            }
        }
    }
    
    private fun handleTaskError(task: DownloadTask, error: Throwable) {
        // 根据错误类型决定重试策略
        when (error) {
            is CancellationException -> {
                // 任务被取消，不需要处理
            }
            else -> {
                // 其他错误，可能需要重试或标记为失败
                // 这里可以集成重试管理器
            }
        }
    }
    
    private fun onNetworkChanged(networkType: NetworkType) {
        // 网络变化时不自动修改并发数，由用户自己控制
        when (networkType) {
            NetworkType.UNKNOWN -> {
                // 无网络，暂停所有任务
                pauseAllTasks()
            }
            else -> {
                // 有网络，触发一次调度
                trySchedule()
            }
        }
    }
    
    private fun onBatteryChanged(isLowBattery: Boolean) {
        // 电量变化时不自动修改并发数，由用户自己控制
        // 低电量时可以暂停后台任务，但不修改并发数
        if (isLowBattery) {
            dispatcher.getBackgroundTasks().forEach { task ->
                engine.pause(task.id)
            }
        } else {
            dispatcher.getBackgroundTasks().forEach { task ->
                engine.resume(task.id)
            }
        }
    }
    
    private fun pauseAllTasks() {
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
    }
    
    fun getRunningTasks(): List<DownloadTask> = dispatcher.getRunningTasks()
    fun getWaitingTasks(): List<DownloadTask> = dispatcher.getWaitingTasks()
    fun getUrgentTasks(): List<DownloadTask> = dispatcher.getUrgentTasks()
    fun getNormalTasks(): List<DownloadTask> = dispatcher.getNormalTasks()
    fun getBackgroundTasks(): List<DownloadTask> = dispatcher.getBackgroundTasks()
    
    fun updateConfig(newConfig: com.pichs.download.config.DownloadConfig) {
        this.config = newConfig
        dispatcher.setMaxConcurrentTasks(newConfig.maxConcurrentTasks)
        // 触发一次调度以应用新配置
        trySchedule()
    }
}
