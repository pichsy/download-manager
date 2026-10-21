package com.pichs.download.core

import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import java.io.File

/**
 * 过滤/排序并跳过与上次相同的过滤结果，避免无关任务的进度刷新触发列表重排。
 */
internal class DownloadTaskListDeriver(
    private val statuses: Set<DownloadStatus>?,
    private val order: Comparator<DownloadTask>?
) {
    private var lastFiltered: List<DownloadTask>? = null

    fun derive(tasks: List<DownloadTask>): List<DownloadTask>? {
        val filtered = if (statuses == null) tasks else tasks.filter { it.status in statuses }
        if (filtered == lastFiltered) return null
        lastFiltered = filtered
        return if (order == null) filtered else filtered.sortedWith(order)
    }
}

/**
 * 将连续的任务快照转换为一次性事件。基线中的状态不会补发事件。
 */
internal class DownloadEventTracker(initialTasks: List<DownloadTask>) {

    private val lastStatuses = HashMap<String, DownloadStatus>(initialTasks.size)

    init {
        initialTasks.forEach { lastStatuses[it.id] = it.status }
    }

    fun update(tasks: List<DownloadTask>): List<DownloadEvent> {
        val events = mutableListOf<DownloadEvent>()
        val aliveIds = tasks.mapTo(HashSet(tasks.size)) { it.id }
        lastStatuses.keys.retainAll { it in aliveIds }

        tasks.forEach { task ->
            val previous = lastStatuses.put(task.id, task.status)
            if (previous == task.status) return@forEach

            when (task.status) {
                DownloadStatus.COMPLETED -> {
                    val completed = task.copy(progress = 100, speed = 0)
                    val file = File(completed.filePath, completed.fileName)
                    if (file.exists()) {
                        events.add(DownloadEvent.Completed(completed, file))
                    }
                }

                DownloadStatus.FAILED -> events.add(DownloadEvent.Failed(task))

                DownloadStatus.PAUSED -> {
                    // 新出现的暂停任务（例如创建即被网络策略暂停）不算状态跃迁
                    if (previous != null) events.add(DownloadEvent.Paused(task))
                }

                DownloadStatus.CANCELLED -> events.add(DownloadEvent.Cancelled(task))

                DownloadStatus.WAITING,
                DownloadStatus.PENDING,
                DownloadStatus.DOWNLOADING -> {
                    if (previous == DownloadStatus.PAUSED || previous == DownloadStatus.FAILED) {
                        events.add(DownloadEvent.Resumed(task))
                    }
                }
            }
        }
        return events
    }
}
