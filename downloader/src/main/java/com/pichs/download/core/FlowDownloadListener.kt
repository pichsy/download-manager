package com.pichs.download.core

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.pichs.download.model.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
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
     * 监听特定任务的状态变化
     */
    fun observeTask(taskId: String): Flow<DownloadTask?> {
        return DownloadManager.tasksState
            .map { tasks: List<DownloadTask> -> tasks.find { it.id == taskId } }
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
            .map { tasks: List<DownloadTask> -> tasks.filter { it.status == com.pichs.download.model.DownloadStatus.DOWNLOADING } }
    }

    /**
     * 监听已完成的任务
     */
    fun observeCompletedTasks(): Flow<List<DownloadTask>> {
        return DownloadManager.tasksState
            .map { tasks: List<DownloadTask> -> tasks.filter { it.status == com.pichs.download.model.DownloadStatus.COMPLETED } }
    }

    /**
     * 监听失败的任务
     */
    fun observeFailedTasks(): Flow<List<DownloadTask>> {
        return DownloadManager.tasksState
            .map { tasks: List<DownloadTask> -> tasks.filter { it.status == com.pichs.download.model.DownloadStatus.FAILED } }
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
        // 监听任务状态变化和进度
        lifecycleOwner.lifecycleScope.launch {
            val activeProgressListeners = mutableSetOf<String>()
            
            observeAllTasks().collect { tasks: List<DownloadTask> ->
                tasks.forEach { task ->
                    when (task.status) {
                        com.pichs.download.model.DownloadStatus.COMPLETED -> {
                            val file = File(task.filePath, task.fileName)
                            if (file.exists()) {
                                onTaskComplete(task, file)
                            }
                        }
                        com.pichs.download.model.DownloadStatus.FAILED -> {
                            onTaskError(task, com.pichs.download.core.DownloadError.NetworkError)
                        }
                        com.pichs.download.model.DownloadStatus.PAUSED -> {
                            onTaskPaused(task)
                        }
                        com.pichs.download.model.DownloadStatus.CANCELLED -> {
                            onTaskCancelled(task)
                        }
                        com.pichs.download.model.DownloadStatus.WAITING,
                        com.pichs.download.model.DownloadStatus.PENDING -> {
                            onTaskResumed(task)
                        }
                        com.pichs.download.model.DownloadStatus.DOWNLOADING -> {
                            // 立即触发一次进度回调，确保 UI 马上更新
                            onTaskProgress(task, task.progress, task.speed)
                            // 为下载中的任务启动进度监听
                            if (!activeProgressListeners.contains(task.id)) {
                                activeProgressListeners.add(task.id)
                                launch {
                                    observeTaskProgress(task.id).collect { (progress, speed) ->
                                        val latestTask = DownloadManager.getTask(task.id)
                                        if (latestTask != null && latestTask.status == com.pichs.download.model.DownloadStatus.DOWNLOADING) {
                                            onTaskProgress(latestTask, progress, speed)
                                        }
                                    }
                                }
                            }
                        }
                        else -> { }
                    }
                }
            }
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
        // 监听任务状态变化和进度
        scope.launch(Dispatchers.Main) {
            val activeProgressListeners = mutableSetOf<String>()
            
            observeAllTasks().collect { tasks: List<DownloadTask> ->
                tasks.forEach { task ->
                    when (task.status) {
                        com.pichs.download.model.DownloadStatus.COMPLETED -> {
                            val file = File(task.filePath, task.fileName)
                            if (file.exists()) {
                                onTaskComplete(task, file)
                            }
                        }
                        com.pichs.download.model.DownloadStatus.FAILED -> {
                            onTaskError(task, com.pichs.download.core.DownloadError.NetworkError)
                        }
                        com.pichs.download.model.DownloadStatus.PAUSED -> {
                            onTaskPaused(task)
                        }
                        com.pichs.download.model.DownloadStatus.CANCELLED -> {
                            onTaskCancelled(task)
                        }
                        com.pichs.download.model.DownloadStatus.WAITING,
                        com.pichs.download.model.DownloadStatus.PENDING -> {
                            onTaskResumed(task)
                        }
                        com.pichs.download.model.DownloadStatus.DOWNLOADING -> {
                            // 立即触发一次进度回调，确保 UI 马上更新
                            onTaskProgress(task, task.progress, task.speed)
                            if (!activeProgressListeners.contains(task.id)) {
                                activeProgressListeners.add(task.id)
                                launch {
                                    observeTaskProgress(task.id).collect { (progress, speed) ->
                                        val latestTask = DownloadManager.getTask(task.id)
                                        if (latestTask != null && latestTask.status == com.pichs.download.model.DownloadStatus.DOWNLOADING) {
                                            onTaskProgress(latestTask, progress, speed)
                                        }
                                    }
                                }
                            }
                        }
                        else -> { }
                    }
                }
            }
        }
    }
}

// 扩展函数，方便使用
fun FlowDownloadListener.observeTaskProgress(
    taskId: String,
    scope: CoroutineScope,
    onProgress: (Int, Long) -> Unit
) {
    scope.launch(Dispatchers.Main) {
        observeTaskProgress(taskId).collect { (progress, speed) ->
            onProgress(progress, speed)
        }
    }
}

fun FlowDownloadListener.observeTaskStatus(
    taskId: String,
    scope: CoroutineScope,
    onStatusChange: (DownloadTask?) -> Unit
) {
    scope.launch(Dispatchers.Main) {
        observeTask(taskId).collect { task ->
            onStatusChange(task)
        }
    }
}