package com.pichs.download.core

import com.pichs.download.model.DownloadTask
import java.io.File

/**
 * 一次性下载事件，适合 Toast、通知、自动安装等副作用。
 * 列表状态请使用 [FlowDownloadListener.observeTasks]。
 */
sealed interface DownloadEvent {
    val task: DownloadTask

    data class Completed(override val task: DownloadTask, val file: File) : DownloadEvent
    data class Failed(override val task: DownloadTask) : DownloadEvent
    data class Paused(override val task: DownloadTask) : DownloadEvent
    data class Resumed(override val task: DownloadTask) : DownloadEvent
    data class Cancelled(override val task: DownloadTask) : DownloadEvent
}
