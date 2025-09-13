package com.pichs.download.core

import com.pichs.download.model.DownloadTask
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

internal class AdvancedDownloadQueueDispatcher {
    
    private val config = SchedulerConfig()
    private val networkMonitor: NetworkMonitor? = null
    
    // 多优先级队列：按优先级和创建时间排序
    private val taskQueue = PriorityBlockingQueue<DownloadTask>(
        11, 
        compareByDescending<DownloadTask> { it.priority }
            .thenBy { it.createTime }
    )
    
    // 运行中的任务：按优先级分组
    private val runningTasks = ConcurrentHashMap<String, DownloadTask>()
    private val urgentTasks = ConcurrentHashMap<String, DownloadTask>()
    private val normalTasks = ConcurrentHashMap<String, DownloadTask>()
    private val backgroundTasks = ConcurrentHashMap<String, DownloadTask>()
    
    // 并发控制
    private val maxConcurrentTasks = AtomicInteger(config.maxConcurrentTasks)
    private val mutex = Mutex()
    
    // 任务抢占锁
    private val preemptionMutex = Mutex()
    
    fun enqueue(task: DownloadTask) {
        taskQueue.offer(task)
        scheduleNext()
    }
    
    fun dequeue(): DownloadTask? {
        if (runningTasks.size >= maxConcurrentTasks.get()) return null
        val task = taskQueue.poll() ?: return null
        runningTasks[task.id] = task
        
        // 按优先级分组
        when (task.priority) {
            3 -> urgentTasks[task.id] = task
            2 -> normalTasks[task.id] = task
            else -> backgroundTasks[task.id] = task
        }
        
        return task
    }
    
    suspend fun dequeueWithPreemption(): DownloadTask? {
        return mutex.withLock {
            // 检查是否有紧急任务需要插队
            val urgentTask = taskQueue.peek()
            if (urgentTask != null && urgentTask.priority >= 3) {
                return@withLock dequeue()
            }
            
            // 检查是否需要抢占低优先级任务
            if (config.enableTaskPreemption && runningTasks.size >= maxConcurrentTasks.get()) {
                val preemptedTask = findPreemptableTask()
                if (preemptedTask != null) {
                    preemptTask(preemptedTask)
                    return@withLock dequeue()
                }
            }
            
            dequeue()
        }
    }
    
    private suspend fun findPreemptableTask(): DownloadTask? {
        return preemptionMutex.withLock {
            // 优先抢占后台任务
            val backgroundTask = backgroundTasks.values.firstOrNull { 
                it.priority <= 1 && !isTaskCritical(it) 
            }
            if (backgroundTask != null) return@withLock backgroundTask
            
            // 其次抢占普通任务（非用户主动）
            val normalTask = normalTasks.values.firstOrNull { 
                it.priority == 2 && !isTaskCritical(it) 
            }
            if (normalTask != null) return@withLock normalTask
            
            null
        }
    }
    
    private suspend fun preemptTask(task: DownloadTask) {
        preemptionMutex.withLock {
            // 暂停任务
            runningTasks.remove(task.id)
            when (task.priority) {
                3 -> urgentTasks.remove(task.id)
                2 -> normalTasks.remove(task.id)
                else -> backgroundTasks.remove(task.id)
            }
            
            // 重新入队，降低优先级
            val preemptedTask = task.copy(priority = maxOf(0, task.priority - 1))
            taskQueue.offer(preemptedTask)
        }
    }
    
    private fun isTaskCritical(task: DownloadTask): Boolean {
        // 判断任务是否关键（如系统更新、用户正在等待的任务等）
        return task.priority >= 3 || 
               task.extras?.contains("critical") == true ||
               task.extras?.contains("user_waiting") == true
    }
    
    fun remove(taskId: String) {
        taskQueue.removeIf { it.id == taskId }
        runningTasks.remove(taskId)
        urgentTasks.remove(taskId)
        normalTasks.remove(taskId)
        backgroundTasks.remove(taskId)
    }
    
    fun getRunningTasks(): List<DownloadTask> = runningTasks.values.toList()
    fun getWaitingTasks(): List<DownloadTask> = taskQueue.toList()
    fun getUrgentTasks(): List<DownloadTask> = urgentTasks.values.toList()
    fun getNormalTasks(): List<DownloadTask> = normalTasks.values.toList()
    fun getBackgroundTasks(): List<DownloadTask> = backgroundTasks.values.toList()
    
    fun setMaxConcurrentTasks(count: Int) { 
        maxConcurrentTasks.set(count)
        scheduleNext()
    }
    
    fun updateConfig(newConfig: SchedulerConfig) {
        maxConcurrentTasks.set(newConfig.maxConcurrentTasks)
        scheduleNext()
    }
    
    fun getCurrentConcurrencyLimit(): Int {
        val networkType = networkMonitor?.getCurrentNetworkType() ?: NetworkType.UNKNOWN
        val isLowBattery = networkMonitor?.isCurrentlyLowBattery() ?: false
        
        return when {
            isLowBattery -> config.maxConcurrentOnLowBattery
            networkType == NetworkType.WIFI -> config.maxConcurrentOnWifi
            networkType in listOf(NetworkType.CELLULAR_4G, NetworkType.CELLULAR_5G) -> config.maxConcurrentOnCellular
            else -> config.maxConcurrentTasks
        }
    }
    
    private fun scheduleNext() {
        // 触发调度逻辑，由外部调用
    }
    
    fun pauseLowPriorityTasks() {
        // 暂停低优先级任务，为紧急任务让路
        backgroundTasks.values.forEach { task ->
            // 这里需要调用引擎的暂停方法
            // engine.pause(task.id)
        }
    }
    
    fun resumeLowPriorityTasks() {
        // 恢复低优先级任务
        backgroundTasks.values.forEach { task ->
            // engine.resume(task.id)
        }
    }
}
