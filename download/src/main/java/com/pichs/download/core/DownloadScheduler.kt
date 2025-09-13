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
    
    private val config = SchedulerConfig()
    
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
        
        // 启动调度循环
        scope.launch {
            while (isActive) {
                scheduleNext()
                delay(1000) // 每秒检查一次
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
        // 引擎暂停逻辑
    }
    
    fun resume(taskId: String) {
        val task = dispatcher.getRunningTasks().find { it.id == taskId }
        if (task != null) {
            startTask(task)
        }
    }
    
    private suspend fun scheduleNext() {
        val currentLimit = dispatcher.getCurrentConcurrencyLimit()
        val runningCount = dispatcher.getRunningTasks().size
        
        if (runningCount < currentLimit) {
            val nextTask = dispatcher.dequeueWithPreemption()
            if (nextTask != null) {
                startTask(nextTask)
            }
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
        when (networkType) {
            NetworkType.WIFI -> {
                // WiFi网络，可以增加并发数
                dispatcher.setMaxConcurrentTasks(config.maxConcurrentOnWifi)
            }
            NetworkType.CELLULAR_4G, NetworkType.CELLULAR_5G -> {
                // 4G/5G网络，中等并发数
                dispatcher.setMaxConcurrentTasks(config.maxConcurrentOnCellular)
            }
            NetworkType.CELLULAR_3G, NetworkType.CELLULAR_2G -> {
                // 3G/2G网络，降低并发数
                dispatcher.setMaxConcurrentTasks(1)
            }
            NetworkType.ETHERNET -> {
                // 以太网，高并发数
                dispatcher.setMaxConcurrentTasks(config.maxConcurrentOnWifi)
            }
            NetworkType.UNKNOWN -> {
                // 无网络，暂停所有任务
                pauseAllTasks()
            }
        }
    }
    
    private fun onBatteryChanged(isLowBattery: Boolean) {
        if (isLowBattery) {
            // 低电量，降低并发数并暂停后台任务
            dispatcher.setMaxConcurrentTasks(config.maxConcurrentOnLowBattery)
            dispatcher.pauseLowPriorityTasks()
        } else {
            // 电量充足，恢复正常并发数
            val networkType = networkMonitor.getCurrentNetworkType()
            when (networkType) {
                NetworkType.WIFI -> dispatcher.setMaxConcurrentTasks(config.maxConcurrentOnWifi)
                NetworkType.CELLULAR_4G, NetworkType.CELLULAR_5G -> dispatcher.setMaxConcurrentTasks(config.maxConcurrentOnCellular)
                else -> dispatcher.setMaxConcurrentTasks(config.maxConcurrentTasks)
            }
            dispatcher.resumeLowPriorityTasks()
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
    
    fun updateConfig(newConfig: SchedulerConfig) {
        dispatcher.updateConfig(newConfig)
    }
}
