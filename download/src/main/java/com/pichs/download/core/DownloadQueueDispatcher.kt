package com.pichs.download.core

import com.pichs.download.model.DownloadTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class DownloadQueueDispatcher {
    private val taskQueue = PriorityBlockingQueue<DownloadTask>(11, compareByDescending<DownloadTask> { it.priority }.thenBy { it.createTime })
    private val runningTasks = ConcurrentHashMap<String, DownloadTask>()
    private val maxConcurrentTasks = AtomicInteger(3)

    fun enqueue(task: DownloadTask) {
        taskQueue.offer(task)
    }

    fun dequeue(): DownloadTask? {
        if (runningTasks.size >= maxConcurrentTasks.get()) return null
        val task = taskQueue.poll() ?: return null
        runningTasks[task.id] = task
        return task
    }

    fun remove(taskId: String) {
        taskQueue.removeIf { it.id == taskId }
        runningTasks.remove(taskId)
    }

    fun getRunningTasks(): List<DownloadTask> = runningTasks.values.toList()
    fun getWaitingTasks(): List<DownloadTask> = taskQueue.toList()

    fun setMaxConcurrentTasks(count: Int) { maxConcurrentTasks.set(count) }
}
