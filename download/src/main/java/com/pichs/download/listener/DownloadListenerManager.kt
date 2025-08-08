package com.pichs.download.listener

import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DownloadListenerManager {
    private val globalListeners = mutableListOf<DownloadListener>()
    private val taskListeners = ConcurrentHashMap<String, MutableList<DownloadListener>>()
    private val progressListeners = ConcurrentHashMap<String, MutableList<ProgressListener>>()
    private val statusListeners = ConcurrentHashMap<String, MutableList<StatusListener>>()

    @Synchronized
    fun addGlobalListener(listener: DownloadListener) {
        globalListeners.add(listener)
    }

    @Synchronized
    fun removeGlobalListener(listener: DownloadListener) {
        globalListeners.remove(listener)
    }

    fun addTaskListener(taskId: String, listener: DownloadListener) {
        taskListeners.getOrPut(taskId) { mutableListOf() }.add(listener)
    }

    fun removeTaskListener(taskId: String, listener: DownloadListener) {
        taskListeners[taskId]?.remove(listener)
    }

    fun addProgressListener(taskId: String, listener: ProgressListener) {
        progressListeners.getOrPut(taskId) { mutableListOf() }.add(listener)
    }

    fun removeProgressListener(taskId: String, listener: ProgressListener) {
        progressListeners[taskId]?.remove(listener)
    }

    fun addStatusListener(taskId: String, listener: StatusListener) {
        statusListeners.getOrPut(taskId) { mutableListOf() }.add(listener)
    }

    fun removeStatusListener(taskId: String, listener: StatusListener) {
        statusListeners[taskId]?.remove(listener)
    }

    fun addBatchListener(taskIds: List<String>, listener: DownloadListener) {
        taskIds.forEach { addTaskListener(it, listener) }
    }

    fun removeBatchListener(taskIds: List<String>, listener: DownloadListener) {
        taskIds.forEach { removeTaskListener(it, listener) }
    }

    fun notifyTaskStart(task: DownloadTask) {
        globalListeners.forEach { it.onTaskStart(task) }
        taskListeners[task.id]?.forEach { it.onTaskStart(task) }
    }

    fun notifyTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
        globalListeners.forEach { it.onTaskProgress(task, progress, speed) }
        taskListeners[task.id]?.forEach { it.onTaskProgress(task, progress, speed) }
        progressListeners[task.id]?.forEach { it.onProgress(task.id, progress, speed) }
    }

    fun notifyTaskComplete(task: DownloadTask, file: File) {
        globalListeners.forEach { it.onTaskComplete(task, file) }
        taskListeners[task.id]?.forEach { it.onTaskComplete(task, file) }
        progressListeners[task.id]?.forEach { it.onComplete(task.id, file) }
    }

    fun notifyTaskError(task: DownloadTask, error: Throwable) {
        globalListeners.forEach { it.onTaskError(task, error) }
        taskListeners[task.id]?.forEach { it.onTaskError(task, error) }
        progressListeners[task.id]?.forEach { it.onError(task.id, error) }
    }

    fun notifyStatusChanged(taskId: String, oldStatus: DownloadStatus, newStatus: DownloadStatus) {
        statusListeners[taskId]?.forEach { it.onStatusChanged(taskId, oldStatus, newStatus) }
    }
}
