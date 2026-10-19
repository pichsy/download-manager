package com.pichs.download.core

import android.content.Context
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.model.PauseReason
import com.pichs.download.utils.DownloadLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val scheduleMutex = Mutex()

    @Volatile
    private var networkAvailable = true

    private var config = com.pichs.download.config.DownloadConfig()

    fun start() {
        networkMonitor.startMonitoring()

        scope.launch {
            networkMonitor.networkType.collect { networkType ->
                onNetworkChanged(networkType)
            }
        }

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
        runningJobs.remove(taskId)?.cancel()
    }

    fun pause(taskId: String) {
        runningJobs.remove(taskId)?.cancel()
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
            scheduleMutex.withLock {
                scheduleNextInternal()
            }
        }
    }

    /**
     * 内部调度逻辑
     * 循环处理直到并发满或队列空
     */
    private suspend fun scheduleNextInternal() {
        if (!networkAvailable) {
            DownloadLog.d(TAG, "当前无网络，暂停调度")
            return
        }

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
                    if (!startTask(task)) {
                        // 上一个协程尚未退出，任务已退回队列，等待其完成后再调度
                        break
                    }
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
                    continue
                } else {
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

        val victim = runningTasks
            .filter { it.priority < urgentPriority }
            .minByOrNull { it.priority }

        if (victim == null) {
            DownloadLog.d(TAG, "无可抢占任务（所有运行中任务优先级 >= $urgentPriority）")
            return false
        }

        DownloadLog.d(TAG, "抢占: ${victim.fileName}(priority=${victim.priority}) 让位，状态改为等待中")

        engine.preempt(victim.id)
        runningJobs.remove(victim.id)?.cancel()

        dispatcher.removeFromRunning(victim.id)

        // 只有确实被转为等待态且仍存在的任务才重新入队，避免复活已删除/已完成的任务
        val latest = DownloadManager.getTaskImmediate(victim.id)
        if (latest != null &&
            (latest.status == DownloadStatus.WAITING || latest.status == DownloadStatus.PENDING)
        ) {
            dispatcher.requeue(latest)
        } else {
            DownloadLog.d(TAG, "被抢占任务状态已变化(${latest?.status})，不重新入队: ${victim.id}")
        }

        val urgentTask = dispatcher.dequeue()
        if (urgentTask != null) {
            DownloadLog.d(TAG, "启动高优先级任务: ${urgentTask.fileName}(priority=${urgentTask.priority})")
            startTask(urgentTask)
        } else {
            DownloadLog.e(TAG, "抢占后 dequeue 返回 null，异常情况")
        }

        return true
    }

    /**
     * @return true 表示已成功启动；false 表示上一个协程仍未退出，任务已退回等待队列
     */
    private fun startTask(task: DownloadTask): Boolean {
        if (!networkAvailable) {
            DownloadLog.d(TAG, "无网络，暂不启动任务: ${task.fileName}")
            // 任务已被 dequeue 计入运行集合，需要退回队列，否则会永久占用并发槽
            dispatcher.removeFromRunning(task.id)
            val current = DownloadManager.getTaskImmediate(task.id)
            if (current == null ||
                current.status == DownloadStatus.WAITING ||
                current.status == DownloadStatus.PENDING
            ) {
                dispatcher.requeue(current ?: task)
            }
            return true
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                engine.start(task)
            } catch (_: CancellationException) {
                // 暂停/取消/抢占导致，状态由调用方维护
            } catch (e: Exception) {
                handleTaskError(task, e)
            }
        }
        val previous = runningJobs.putIfAbsent(task.id, job)
        if (previous != null) {
            // 暂停后立即恢复时，旧协程可能尚未执行完 finally。
            // 不能直接丢弃已出队的任务，否则它会永远停在 WAITING。
            DownloadLog.w(TAG, "任务仍有退出中的协程，延后启动: ${task.id}")
            job.cancel()
            val current = DownloadManager.getTaskImmediate(task.id)
            if (current != null && !current.status.isTerminalOrPaused) {
                dispatcher.requeue(current)
                // 旧协程的完成回调也可能错过这次入队，补一次延迟调度
                scope.launch {
                    kotlinx.coroutines.delay(100)
                    trySchedule()
                }
            }
            return false
        }

        job.invokeOnCompletion { cause ->
            val removed = runningJobs.remove(task.id, job)
            if (removed) {
                dispatcher.removeFromRunning(task.id)
                if (cause != null && cause !is CancellationException) {
                    handleTaskError(task, cause)
                }
                // 无论是否取消都要尝试调度：被退回队列的任务依赖这里重新启动
                trySchedule()
            }
        }
        job.start()
        return true
    }

    private val DownloadStatus.isTerminalOrPaused: Boolean
        get() = this == DownloadStatus.COMPLETED ||
                this == DownloadStatus.CANCELLED ||
                this == DownloadStatus.PAUSED ||
                this == DownloadStatus.FAILED

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
        val available = networkType != NetworkType.UNKNOWN
        if (!available) {
            if (networkAvailable) {
                DownloadLog.d(TAG, "网络断开，暂停所有任务")
            }
            networkAvailable = false
            pauseAllForNetworkError()
        } else {
            val recovered = !networkAvailable
            networkAvailable = true
            if (recovered) {
                DownloadLog.d(TAG, "网络恢复: $networkType")
                DownloadManager.onNetworkRestored()
            }
            trySchedule()
        }
    }

    private fun onBatteryChanged(isLowBattery: Boolean) {
        if (isLowBattery) {
            dispatcher.getBackgroundTasks().forEach { task ->
                DownloadManager.pauseTask(task.id, PauseReason.BATTERY_LOW)
            }
        } else {
            DownloadManager.resumeAll(PauseReason.BATTERY_LOW)
        }
    }

    private fun pauseAllForNetworkError() {
        val active = dispatcher.getRunningTasks() + dispatcher.getWaitingTasks()
        active.distinctBy { it.id }.forEach { task ->
            DownloadManager.pauseTask(task.id, PauseReason.NETWORK_ERROR)
        }
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
        trySchedule()
    }
}
