package com.pichs.download.core

import com.pichs.download.model.DownloadTask

/**
 * 下载引擎占位：后续接入 OkHttp + 协程 + 断点续传
 */
internal interface DownloadEngine {
    suspend fun start(task: DownloadTask)
    fun pause(taskId: String)
    fun resume(taskId: String)
    fun cancel(taskId: String)
    
    /**
     * 抢占：停止下载但任务状态改为 WAITING（等待中），而不是 PAUSED
     * 当高优先级任务需要抢占低优先级任务时使用
     */
    fun preempt(taskId: String)
}
