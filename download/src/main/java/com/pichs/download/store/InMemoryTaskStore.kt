package com.pichs.download.store

import com.pichs.download.model.DownloadTask
import java.util.concurrent.ConcurrentHashMap

internal object InMemoryTaskStore {
    private val tasks = ConcurrentHashMap<String, DownloadTask>()

    fun put(task: DownloadTask) { tasks[task.id] = task }
    fun get(id: String): DownloadTask? = tasks[id]
    fun getAll(): List<DownloadTask> = tasks.values.toList()
    fun remove(id: String) { tasks.remove(id) }
}
