package com.pichs.download.core

import com.pichs.download.model.DownloadTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载队列调度器（纯数据结构）
 * 
 * 职责：
 * - 管理等待队列（按优先级排序）
 * - 维护运行中任务映射（用于并发控制）
 * - 提供入队/出队/查询操作
 * 
 * 不负责：
 * - 抢占逻辑（由 DownloadScheduler 处理）
 * - 实际暂停/恢复下载（由 Engine 处理）
 */
internal class AdvancedDownloadQueueDispatcher {
    
    private val config = SchedulerConfig()

    // 等待队列：按优先级降序 + 创建时间升序
    private val taskQueue = PriorityBlockingQueue<DownloadTask>(
        11, 
        compareByDescending<DownloadTask> { it.priority }
            .thenBy { it.createTime }
    )
    
    // 运行中的任务：总映射 + 按优先级分组
    private val runningTasks = ConcurrentHashMap<String, DownloadTask>()
    private val urgentTasks = ConcurrentHashMap<String, DownloadTask>()
    private val normalTasks = ConcurrentHashMap<String, DownloadTask>()
    private val backgroundTasks = ConcurrentHashMap<String, DownloadTask>()
    
    // 并发控制
    private val maxConcurrentTasks = AtomicInteger(config.maxConcurrentTasks)
    
    /**
     * 入队：添加任务到等待队列
     */
    fun enqueue(task: DownloadTask) {
        taskQueue.offer(task)
    }
    
    /**
     * 出队：从等待队列取出最高优先级任务，加入运行映射
     * @return 取出的任务，如果并发已满或队列为空，返回 null
     */
    fun dequeue(): DownloadTask? {
        if (runningTasks.size >= maxConcurrentTasks.get()) return null
        val task = taskQueue.poll() ?: return null
        
        // 加入运行映射
        runningTasks[task.id] = task
        
        // 按优先级分组
        when (task.priority) {
            3 -> urgentTasks[task.id] = task
            2 -> normalTasks[task.id] = task
            else -> backgroundTasks[task.id] = task
        }
        
        return task
    }
    
    /**
     * 查看队首：获取等待队列中优先级最高的任务（不出队）
     */
    fun peek(): DownloadTask? = taskQueue.peek()
    
    /**
     * 从运行映射中移除任务（抢占时使用）
     * 注意：这只更新 Dispatcher 的状态映射，不会暂停实际下载
     */
    fun removeFromRunning(taskId: String) {
        val task = runningTasks.remove(taskId) ?: return
        when (task.priority) {
            3 -> urgentTasks.remove(taskId)
            2 -> normalTasks.remove(taskId)
            else -> backgroundTasks.remove(taskId)
        }
    }
    
    /**
     * 重新入队：将被抢占的任务放回等待队列
     */
    fun requeue(task: DownloadTask) {
        taskQueue.offer(task)
    }
    
    /**
     * 移除任务：从等待队列和运行映射中都移除
     */
    fun remove(taskId: String) {
        taskQueue.removeIf { it.id == taskId }
        runningTasks.remove(taskId)
        urgentTasks.remove(taskId)
        normalTasks.remove(taskId)
        backgroundTasks.remove(taskId)
    }
    
    // ==================== 查询方法 ====================
    
    fun getRunningTasks(): List<DownloadTask> = runningTasks.values.toList()
    fun getWaitingTasks(): List<DownloadTask> = taskQueue.toList()
    fun getUrgentTasks(): List<DownloadTask> = urgentTasks.values.toList()
    fun getNormalTasks(): List<DownloadTask> = normalTasks.values.toList()
    fun getBackgroundTasks(): List<DownloadTask> = backgroundTasks.values.toList()
    
    fun getRunningCount(): Int = runningTasks.size
    fun getWaitingCount(): Int = taskQueue.size
    
    // ==================== 配置方法 ====================
    
    fun setMaxConcurrentTasks(count: Int) { 
        maxConcurrentTasks.set(count)
    }
    
    fun updateConfig(newConfig: SchedulerConfig) {
        maxConcurrentTasks.set(newConfig.maxConcurrentTasks)
    }
    
    fun getCurrentConcurrencyLimit(): Int = maxConcurrentTasks.get()
}
