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
        lifecycleOwner.lifecycleScope.launch {
            // 监听任务状态变化
            observeAllTasks().collect { tasks: List<DownloadTask> ->
                tasks.forEach { task ->
                    when (task.status) {
                        com.pichs.download.model.DownloadStatus.COMPLETED -> {
                            // 完成，触发完成回调
                            val file = File(task.filePath, task.fileName)
                            if (file.exists()) {
                                onTaskComplete(task, file)
                            }
                        }
                        com.pichs.download.model.DownloadStatus.FAILED -> {
                            // 失败，触发错误回调
                            onTaskError(task, com.pichs.download.core.DownloadError.NetworkError)
                        }
                        com.pichs.download.model.DownloadStatus.PAUSED -> {
                            // 暂停，触发暂停回调
                            onTaskPaused(task)
                        }
                        com.pichs.download.model.DownloadStatus.CANCELLED -> {
                            // 取消，触发取消回调
                            onTaskCancelled(task)
                        }
                        else -> {
                            // 其他状态，如WAITING, PENDING等
                        }
                    }
                }
            }
        }
        
        // 单独监听每个任务的进度变化（优化：减少重复监听）
        lifecycleOwner.lifecycleScope.launch {
            val activeProgressListeners = mutableSetOf<String>()
            
            observeAllTasks().collect { tasks: List<DownloadTask> ->
                val downloadingTasks = tasks.filter { it.status == com.pichs.download.model.DownloadStatus.DOWNLOADING }
                
                // 启动新任务的进度监听
                downloadingTasks.forEach { task ->
                    if (!activeProgressListeners.contains(task.id)) {
                        activeProgressListeners.add(task.id)
                        launch {
                            observeTaskProgress(task.id).collect { (progress, speed) ->
                                // 获取最新的任务状态
                                val latestTask = DownloadManager.getTask(task.id)
                                if (latestTask != null && latestTask.status == com.pichs.download.model.DownloadStatus.DOWNLOADING) {
                                    onTaskProgress(latestTask, progress, speed)
                                }
                            }
                        }
                    }
                }
                
                // 清理已完成任务的监听
                val completedTaskIds = tasks.filter { 
                    it.status != com.pichs.download.model.DownloadStatus.DOWNLOADING 
                }.map { it.id }
                activeProgressListeners.removeAll(completedTaskIds)
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
        scope.launch(Dispatchers.Main) {
            // 监听任务状态变化
            observeAllTasks().collect { tasks: List<DownloadTask> ->
                tasks.forEach { task ->
                    when (task.status) {
                        com.pichs.download.model.DownloadStatus.COMPLETED -> {
                            // 完成，触发完成回调
                            val file = File(task.filePath, task.fileName)
                            if (file.exists()) {
                                onTaskComplete(task, file)
                            }
                        }
                        com.pichs.download.model.DownloadStatus.FAILED -> {
                            // 失败，触发错误回调
                            onTaskError(task, com.pichs.download.core.DownloadError.NetworkError)
                        }
                        com.pichs.download.model.DownloadStatus.PAUSED -> {
                            // 暂停，触发暂停回调
                            onTaskPaused(task)
                        }
                        com.pichs.download.model.DownloadStatus.CANCELLED -> {
                            // 取消，触发取消回调
                            onTaskCancelled(task)
                        }
                        else -> {
                            // 其他状态，如WAITING, PENDING等
                        }
                    }
                }
            }
        }
        
        // 单独监听每个任务的进度变化
        scope.launch(Dispatchers.Main) {
            observeAllTasks().collect { tasks: List<DownloadTask> ->
                tasks.forEach { task ->
                    if (task.status == com.pichs.download.model.DownloadStatus.DOWNLOADING) {
                        // 为每个下载中的任务启动进度监听
                        launch {
                            observeTaskProgress(task.id).collect { (progress, speed) ->
                                // 获取最新的任务状态
                                val latestTask = DownloadManager.getTask(task.id)
                                if (latestTask != null) {
                                    onTaskProgress(latestTask, progress, speed)
                                }
                            }
                        }
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