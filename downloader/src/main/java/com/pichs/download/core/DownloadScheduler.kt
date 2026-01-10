package com.pichs.download.core

import android.content.Context
import com.pichs.download.model.DownloadTask
import com.pichs.download.utils.DownloadLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载调度器
 * 
 * 职责：
 * - 调度任务执行（何时启动下一个任务）
 * - 抢占逻辑（高优先级任务抢占低优先级）
 * - 管理运行中的协程 Job
 * - 响应网络/电池状态变化
 */
internal class DownloadScheduler(
    private val context: Context,
    private val engine: DownloadEngine,
    private val dispatcher: AdvancedDownloadQueueDispatcher
) {
    
    companion object {
        private const val TAG = "DownloadScheduler"
    }
    
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
    }
    
    fun resume(taskId: String) {
        val task = dispatcher.getRunningTasks().find { it.id == taskId }
        if (task != null) {
            startTask(task)
        }
    }
    
    /**
     * 触发调度（公开方法，供 Manager 调用）
     */
    fun trySchedule() {
        DownloadLog.d(TAG, "trySchedule() called")
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            scheduleNextInternal()
        }
    }

    /**
     * 内部调度逻辑
     * 循环处理直到并发满或队列空
     */
    private suspend fun scheduleNextInternal() {
        val currentLimit = dispatcher.getCurrentConcurrencyLimit()
        
        while (true) {
            val runningCount = dispatcher.getRunningCount()
            val waitingCount = dispatcher.getWaitingCount()
            
            DownloadLog.d(TAG, "scheduleNextInternal: limit=$currentLimit, running=$runningCount, waiting=$waitingCount")
            
            if (waitingCount == 0) {
                DownloadLog.d(TAG, "队列为空，停止调度")
                break
            }
            
            // 情况1：有空位，直接出队执行
            if (runningCount < currentLimit) {
                val task = dispatcher.dequeue()
                if (task != null) {
                    DownloadLog.d(TAG, "启动任务: ${task.fileName} (priority=${task.priority})")
                    startTask(task)
                    // 继续循环，尝试填满并发槽
                    continue
                } else {
                    DownloadLog.d(TAG, "dequeue 返回 null，停止调度")
                    break
                }
            }
            
            // 情况2：并发已满，检查是否需要抢占
            val nextTask = dispatcher.peek()
            if (nextTask != null && nextTask.priority >= DownloadPriority.URGENT.value) {
                DownloadLog.d(TAG, "并发已满，尝试抢占: ${nextTask.fileName} (priority=${nextTask.priority})")
                val preempted = tryPreempt(nextTask.priority)
                if (preempted) {
                    // 抢占成功，继续循环，可能还有其他高优先级任务
                    continue
                } else {
                    // 抢占失败（所有运行中任务优先级都 >= urgentPriority），停止循环
                    DownloadLog.d(TAG, "抢占失败，等待空位")
                    break
                }
            } else {
                DownloadLog.d(TAG, "并发已满，等待空位")
                break
            }
        }
    }
    
    /**
     * 尝试抢占：暂停一个低优先级任务，让高优先级任务执行
     * 被抢占的任务状态改为 WAITING（等待中），而不是 PAUSED
     * @return true 如果成功抢占，false 如果没有可抢占的任务
     */
    private fun tryPreempt(urgentPriority: Int): Boolean {
        val runningTasks = dispatcher.getRunningTasks()
        DownloadLog.d(TAG, "tryPreempt: 当前运行中任务数=${runningTasks.size}")
        runningTasks.forEach { 
            DownloadLog.d(TAG, "  运行中: ${it.fileName} (priority=${it.priority})") 
        }
        
        // 找到可被抢占的任务（优先级最低的）
        val victim = runningTasks
            .filter { it.priority < urgentPriority }
            .minByOrNull { it.priority }
        
        if (victim == null) {
            DownloadLog.d(TAG, "无可抢占任务（所有运行中任务优先级 >= $urgentPriority）")
            return false  // 抢占失败
        }
        
        DownloadLog.d(TAG, "抢占: ${victim.fileName}(priority=${victim.priority}) 让位，状态改为等待中")
        
        // 1. 停止被抢占任务的下载（不改为暂停状态，而是等待状态）
        engine.preempt(victim.id)  // 使用 preempt 而不是 pause
        runningJobs[victim.id]?.cancel()
        runningJobs.remove(victim.id)
        
        // 2. 从运行映射移除，放回等待队列
        dispatcher.removeFromRunning(victim.id)
        dispatcher.requeue(victim)
        
        // 3. 取出高优先级任务执行
        val urgentTask = dispatcher.dequeue()
        if (urgentTask != null) {
            DownloadLog.d(TAG, "启动高优先级任务: ${urgentTask.fileName}(priority=${urgentTask.priority})")
            startTask(urgentTask)
        } else {
            DownloadLog.e(TAG, "抢占后 dequeue 返回 null，异常情况")
        }
        
        return true  // 抢占成功
    }
    
    private fun startTask(task: DownloadTask) {
        val job = scope.launch {
            try {
                engine.start(task)
            } catch (e: Exception) {
                handleTaskError(task, e)
            }
        }
        runningJobs[task.id] = job
        
        job.invokeOnCompletion { cause ->
            runningJobs.remove(task.id)
            if (cause != null && cause !is CancellationException) {
                handleTaskError(task, cause)
            }
        }
    }
    
    private fun handleTaskError(task: DownloadTask, error: Throwable) {
        when (error) {
            is CancellationException -> {
                // 任务被取消，不需要处理
            }
            else -> {
                DownloadLog.e(TAG, "任务执行失败: ${task.fileName}", error)
            }
        }
    }
    
    private fun onNetworkChanged(networkType: NetworkType) {
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
    
    // ==================== 查询方法（委托给 Dispatcher）====================
    
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
