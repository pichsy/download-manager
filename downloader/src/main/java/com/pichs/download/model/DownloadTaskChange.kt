package com.pichs.download.model

/**
 * 一次任务快照的变化类型，用于列表局部刷新。
 */
enum class DownloadTaskChange {
    NONE,
    PROGRESS,
    CONTENT
}

/**
 * 对比上一份快照，判断是否只需要刷新进度。
 * 进度字段为 progress、currentSize、speed、updateTime。
 */
fun DownloadTask.diffFrom(previous: DownloadTask): DownloadTaskChange {
    if (this === previous || this == previous) return DownloadTaskChange.NONE
    if (id != previous.id) return DownloadTaskChange.CONTENT
    val progressOnly = copy(
        progress = previous.progress,
        currentSize = previous.currentSize,
        speed = previous.speed,
        updateTime = previous.updateTime
    ) == previous
    return if (progressOnly) DownloadTaskChange.PROGRESS else DownloadTaskChange.CONTENT
}
