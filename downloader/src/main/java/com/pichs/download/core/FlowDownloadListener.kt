package com.pichs.download.core

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.utils.DownloadLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * 基于Flow的下载监听器
 * 提供更现代化的响应式监听方式
 */
class FlowDownloadListener {

    /**
     * 监听所有任务状态变化
     */
    fun observeAllTasks(): Flow<List<DownloadTask>> {
        return DownloadManager.tasksState
    }

    /**
     * 监听过滤、排序后的任务列表。
     *
     * 不传参数时直接复用 [DownloadManager.tasksState]；列表页建议配合
     * ListAdapter + [com.pichs.download.model.DownloadTask.diffFrom] 使用。
     *
     * @param statuses 需要展示的状态，null 表示全部
     * @param order 排序方式，null 表示沿用任务创建时间升序
     */
    fun observeTasks(
        statuses: Set<DownloadStatus>? = null,
        order: Comparator<DownloadTask>? = null
    ): Flow<List<DownloadTask>> {
        if (statuses == null && order == null) return DownloadManager.tasksState
        return flow {
            val deriver = DownloadTaskListDeriver(statuses, order)
            DownloadManager.tasksState.collect { tasks ->
                deriver.derive(tasks)?.let { emit(it) }
            }
        }
    }

    /**
     * 监听特定任务的状态变化
     */
    fun observeTask(taskId: String): Flow<DownloadTask?> {
        return DownloadManager.tasksState
            .map { tasks: List<DownloadTask> -> tasks.find { it.id == taskId } }
            .distinctUntilChanged()
    }

    /**
     * 监听特定任务的进度变化
     */
    fun observeTaskProgress(taskId: String): Flow<Pair<Int, Long>> {
        return DownloadManager.getProgressFlow(taskId)
    }

    /**
     * 监听正在下载的任务
     */
    fun observeRunningTasks(): Flow<List<DownloadTask>> {
        return DownloadManager.tasksState
            .map { tasks: List<DownloadTask> -> tasks.filter { it.status == DownloadStatus.DOWNLOADING } }
    }

    /**
     * 监听已完成的任务
     */
    fun observeCompletedTasks(): Flow<List<DownloadTask>> {
        return DownloadManager.tasksState
            .map { tasks: List<DownloadTask> -> tasks.filter { it.status == DownloadStatus.COMPLETED } }
    }

    /**
     * 监听失败的任务
     */
    fun observeFailedTasks(): Flow<List<DownloadTask>> {
        return DownloadManager.tasksState
            .map { tasks: List<DownloadTask> -> tasks.filter { it.status == DownloadStatus.FAILED } }
    }

    /**
     * 监听一次性下载事件。
     *
     * 订阅时以当前快照为基线，只上报订阅之后发生的状态跃迁，旋转/重进页面不会
     * 重复回调历史完成、暂停事件。事件是冷流，全局副作用请在 Application 或
     * Service 作用域订阅。
     */
    fun observeEvents(): Flow<DownloadEvent> = flow {
        val tracker = DownloadEventTracker(DownloadManager.getAllTasks())
        DownloadManager.tasksState.collect { tasks ->
            // 恢复完成前 StateFlow 可能还是初始空列表，不能让它清空基线
            if (!DownloadManager.tasksRestored && tasks.isEmpty()) return@collect
            tracker.update(tasks).forEach { emit(it) }
        }
    }

    /**
     * 在LifecycleOwner中自动管理监听
     */
    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        onTaskProgress: (DownloadTask, Int, Long) -> Unit = { _, _, _ -> },
        onTaskComplete: (DownloadTask, File) -> Unit = { _, _ -> },
        onTaskError: (DownloadTask, com.pichs.download.core.DownloadError) -> Unit = { _, _ -> },
        onTaskPaused: (DownloadTask) -> Unit = { _ -> },
        onTaskResumed: (DownloadTask) -> Unit = { _ -> },
        onTaskCancelled: (DownloadTask) -> Unit = { _ -> }
    ) {
        lifecycleOwner.lifecycleScope.launch(downloadCallbackDispatcher()) {
            collectTaskStates(
                onTaskProgress = onTaskProgress,
                onTaskComplete = onTaskComplete,
                onTaskError = onTaskError,
                onTaskPaused = onTaskPaused,
                onTaskResumed = onTaskResumed,
                onTaskCancelled = onTaskCancelled
            )
        }
    }

    /**
     * 在CoroutineScope中手动管理监听
     */
    fun bindToScope(
        scope: CoroutineScope,
        onTaskProgress: (DownloadTask, Int, Long) -> Unit = { _, _, _ -> },
        onTaskComplete: (DownloadTask, File) -> Unit = { _, _ -> },
        onTaskError: (DownloadTask, com.pichs.download.core.DownloadError) -> Unit = { _, _ -> },
        onTaskPaused: (DownloadTask) -> Unit = { _ -> },
        onTaskResumed: (DownloadTask) -> Unit = { _ -> },
        onTaskCancelled: (DownloadTask) -> Unit = { _ -> }
    ) {
        scope.launch(downloadCallbackDispatcher()) {
            collectTaskStates(
                onTaskProgress = onTaskProgress,
                onTaskComplete = onTaskComplete,
                onTaskError = onTaskError,
                onTaskPaused = onTaskPaused,
                onTaskResumed = onTaskResumed,
                onTaskCancelled = onTaskCancelled
            )
        }
    }

    private suspend fun collectTaskStates(
        onTaskProgress: (DownloadTask, Int, Long) -> Unit,
        onTaskComplete: (DownloadTask, File) -> Unit,
        onTaskError: (DownloadTask, com.pichs.download.core.DownloadError) -> Unit,
        onTaskPaused: (DownloadTask) -> Unit,
        onTaskResumed: (DownloadTask) -> Unit,
        onTaskCancelled: (DownloadTask) -> Unit
    ) = coroutineScope {
        // 每个下载中任务一个进度订阅；任务暂停/完成/删除时取消，恢复时重新订阅
        val progressJobs = mutableMapOf<String, Job>()
        val lastStatusMap = mutableMapOf<String, DownloadStatus>()
        // 记录最后一次进度回调，避免状态流和进度流重复回调同一进度
        val lastProgressMap = mutableMapOf<String, Pair<Int, Long>>()

        fun emitProgress(task: DownloadTask, progress: Int, speed: Long) {
            val value = progress to speed
            if (lastProgressMap[task.id] == value) return
            lastProgressMap[task.id] = value
            runCatching { onTaskProgress(task, progress, speed) }
                .onFailure { DownloadLog.w("FlowDownloadListener", "进度回调异常: ${task.id}", it) }
        }

        fun stopProgressListener(taskId: String) {
            progressJobs.remove(taskId)?.cancel()
        }

        observeAllTasks().collect { tasks: List<DownloadTask> ->
            val aliveIds = tasks.mapTo(HashSet(tasks.size)) { it.id }
            // 清理已删除任务的订阅和状态
            progressJobs.keys.retainAll { it in aliveIds }
            lastStatusMap.keys.retainAll { it in aliveIds }
            lastProgressMap.keys.retainAll { it in aliveIds }

            tasks.forEach { task ->
                val lastStatus = lastStatusMap[task.id]
                val currentStatus = task.status
                val statusChanged = lastStatus != currentStatus
                lastStatusMap[task.id] = currentStatus

                when (currentStatus) {
                    DownloadStatus.COMPLETED -> {
                        stopProgressListener(task.id)
                        if (statusChanged) {
                            // 进度流可能已经上报过 100%，这里只补发缺失的最终进度
                            if (lastProgressMap[task.id]?.first != 100) {
                                val completedTask = task.copy(progress = 100, speed = 0)
                                emitProgress(completedTask, 100, 0)
                            }
                            val file = File(task.filePath, task.fileName)
                            if (file.exists()) {
                                onTaskComplete(task.copy(progress = 100, speed = 0), file)
                            }
                        }
                    }

                    DownloadStatus.FAILED -> {
                        stopProgressListener(task.id)
                        if (statusChanged) {
                            onTaskError(task, com.pichs.download.core.DownloadError.NetworkError)
                        }
                    }

                    DownloadStatus.PAUSED -> {
                        stopProgressListener(task.id)
                        if (statusChanged) {
                            onTaskPaused(task)
                        }
                    }

                    DownloadStatus.CANCELLED -> {
                        stopProgressListener(task.id)
                        if (statusChanged) {
                            onTaskCancelled(task)
                        }
                    }

                    DownloadStatus.WAITING,
                    DownloadStatus.PENDING -> {
                        stopProgressListener(task.id)
                        if (statusChanged) {
                            onTaskResumed(task)
                        }
                    }

                    DownloadStatus.DOWNLOADING -> {
                        if (statusChanged) {
                            emitProgress(task, task.progress, task.speed)
                        }
                        if (progressJobs[task.id] == null) {
                            progressJobs[task.id] = launch {
                                observeTaskProgress(task.id)
                                    .conflate()
                                    .collect progressCollect@ { (progress, speed) ->
                                        val latestTask = DownloadManager.getTask(task.id)
                                            ?: return@progressCollect
                                        if (latestTask.status == DownloadStatus.DOWNLOADING) {
                                            emitProgress(latestTask, progress, speed)
                                        }
                                    }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 回调线程由 DownloadConfig.callbackOnMain 控制：
 * true（默认）在主线程派发，false 在 Default 线程派发。
 */
internal fun downloadCallbackDispatcher(): kotlinx.coroutines.CoroutineDispatcher =
    if (DownloadManager.currentConfig().callbackOnMain) {
        Dispatchers.Main
    } else {
        Dispatchers.Default
    }

// 扩展函数，方便使用, 单个
fun FlowDownloadListener.observeSingleTaskProgress(
    taskId: String,
    scope: CoroutineScope,
    onProgress: (progress: Int, speed: Long) -> Unit
) {
    scope.launch(downloadCallbackDispatcher()) {
        observeTaskProgress(taskId)
            .conflate()
            .collect { (progress, speed) ->
                onProgress(progress, speed)
            }
    }
}

fun FlowDownloadListener.observeTaskStatus(
    taskId: String,
    scope: CoroutineScope,
    onStatusChange: (DownloadTask?) -> Unit
) {
    scope.launch(downloadCallbackDispatcher()) {
        observeTask(taskId).collect { task ->
            onStatusChange(task)
        }
    }
}
