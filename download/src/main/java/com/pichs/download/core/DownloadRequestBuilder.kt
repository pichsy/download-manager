package com.pichs.download.core

import androidx.lifecycle.LifecycleOwner
import com.pichs.download.listener.DownloadListener
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import java.io.File
import java.util.UUID

class DownloadRequestBuilder {
    private var url: String = ""
    private var path: String = ""
    private var fileName: String? = null
    private var tag: String? = null
    private var priority: Int = 0
    private var headers: Map<String, String> = emptyMap()

    private var onProgressCb: ((Int, Long) -> Unit)? = null
    private var onCompleteCb: ((File) -> Unit)? = null
    private var onErrorCb: ((Throwable) -> Unit)? = null

    fun url(url: String) = apply { this.url = url }
    fun to(path: String, fileName: String? = null) = apply { this.path = path; this.fileName = fileName }
    fun tag(tag: String) = apply { this.tag = tag }
    fun priority(priority: Int) = apply { this.priority = priority }
    fun headers(headers: Map<String, String>) = apply { this.headers = headers }

    fun onProgress(callback: (Int, Long) -> Unit) = apply { this.onProgressCb = callback }
    fun onComplete(callback: (File) -> Unit) = apply { this.onCompleteCb = callback }
    fun onError(callback: (Throwable) -> Unit) = apply { this.onErrorCb = callback }

    fun bindLifecycle(lifecycleOwner: LifecycleOwner) = apply { /* todo */ }

    fun start(): DownloadTask {
        val id = UUID.randomUUID().toString()
        val task = DownloadTask(
            id = id,
            url = url,
            fileName = fileName ?: id,
            filePath = path,
            status = DownloadStatus.PENDING,
            progress = 0,
            totalSize = 0,
            currentSize = 0,
            speed = 0,
            priority = priority,
            createTime = System.currentTimeMillis(),
            updateTime = System.currentTimeMillis()
        )
        // 注册任务，派发开始事件并启动
        DownloadManager.onTaskCreated(task)

        // 回调监听桥接
        if (onProgressCb != null || onCompleteCb != null || onErrorCb != null) {
            DownloadManager.addTaskListener(task.id, object : DownloadListener {
                override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
                    onProgressCb?.invoke(progress, speed)
                }

                override fun onTaskComplete(task: DownloadTask, file: File) {
                    onCompleteCb?.invoke(file)
                }

                override fun onTaskError(task: DownloadTask, error: Throwable) {
                    onErrorCb?.invoke(error)
                }
            })
        }
        return task
    }
}
