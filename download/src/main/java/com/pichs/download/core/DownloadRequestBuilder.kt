package com.pichs.download.core

import androidx.lifecycle.LifecycleOwner
import android.os.Handler
import android.os.Looper
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
    private var packageName: String? = null
    private var storeVersionCode: Long? = null
    private var extras: String? = null

    private var onProgressCb: ((Int, Long) -> Unit)? = null
    private var onCompleteCb: ((File) -> Unit)? = null
    private var onErrorCb: ((Throwable) -> Unit)? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun url(url: String) = apply { this.url = url }
    fun to(path: String, fileName: String? = null) = apply { this.path = path; this.fileName = fileName }
    fun tag(tag: String) = apply { this.tag = tag }
    fun priority(priority: Int) = apply { this.priority = priority }
    fun headers(headers: Map<String, String>) = apply { this.headers = headers }
    fun meta(packageName: String?, storeVersionCode: Long?) = apply { this.packageName = packageName; this.storeVersionCode = storeVersionCode }
    fun extras(extras: String?) = apply { this.extras = extras }

    fun onProgress(callback: (Int, Long) -> Unit) = apply { this.onProgressCb = callback }
    fun onComplete(callback: (File) -> Unit) = apply { this.onCompleteCb = callback }
    fun onError(callback: (Throwable) -> Unit) = apply { this.onErrorCb = callback }

    fun bindLifecycle(lifecycleOwner: LifecycleOwner) = apply { /* todo */ }

    fun start(): DownloadTask {
        val targetName = fileName ?: UUID.randomUUID().toString()
        fun normalize(n: String) = n.substringBeforeLast('.').lowercase()

        // 去重（名称归一化）
        DownloadManager.findExistingTask(url, path, targetName)?.let { existing ->
            // 将回调桥接到已存在任务
            if (onProgressCb != null || onCompleteCb != null || onErrorCb != null) {
                DownloadManager.addTaskListener(existing.id, object : DownloadListener {
                    override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
                        onProgressCb?.let { cb ->
                            if (Looper.myLooper() == Looper.getMainLooper()) cb(progress, speed)
                            else mainHandler.post { cb(progress, speed) }
                        }
                    }
                    override fun onTaskComplete(task: DownloadTask, file: File) {
                        onCompleteCb?.let { cb ->
                            if (Looper.myLooper() == Looper.getMainLooper()) cb(file)
                            else mainHandler.post { cb(file) }
                        }
                    }
                    override fun onTaskError(task: DownloadTask, error: Throwable) {
                        onErrorCb?.let { cb ->
                            if (Looper.myLooper() == Looper.getMainLooper()) cb(error)
                            else mainHandler.post { cb(error) }
                        }
                    }
                })
            }
            return existing
        } ?: run {
            // 再次遍历，兼容已存在任务文件名附带/未附带后缀
            val byNormalized = DownloadManager.getAllTasks().firstOrNull {
                it.url == url && it.filePath == path && normalize(it.fileName) == normalize(targetName)
            }
            if (byNormalized != null) {
                if (onProgressCb != null || onCompleteCb != null || onErrorCb != null) {
                    DownloadManager.addTaskListener(byNormalized.id, object : DownloadListener {
                        override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) { /* bridge */ }
                        override fun onTaskComplete(task: DownloadTask, file: File) { /* bridge */ }
                        override fun onTaskError(task: DownloadTask, error: Throwable) { /* bridge */ }
                    })
                }
                return byNormalized
            }
        }

        val id = UUID.randomUUID().toString()
        val task = DownloadTask(
            id = id,
            url = url,
            fileName = targetName,
            filePath = path,
            status = DownloadStatus.PENDING,
            progress = 0,
            totalSize = 0,
            currentSize = 0,
            speed = 0,
            priority = priority,
            createTime = System.currentTimeMillis(),
            updateTime = System.currentTimeMillis(),
            packageName = packageName,
            storeVersionCode = storeVersionCode,
            extras = extras
        )
        DownloadManager.onTaskCreated(task)

        if (onProgressCb != null || onCompleteCb != null || onErrorCb != null) {
            DownloadManager.addTaskListener(task.id, object : DownloadListener {
                override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
                    onProgressCb?.let { cb ->
                        if (Looper.myLooper() == Looper.getMainLooper()) cb(progress, speed)
                        else mainHandler.post { cb(progress, speed) }
                    }
                }
                override fun onTaskComplete(task: DownloadTask, file: File) {
                    onCompleteCb?.let { cb ->
                        if (Looper.myLooper() == Looper.getMainLooper()) cb(file)
                        else mainHandler.post { cb(file) }
                    }
                }
                override fun onTaskError(task: DownloadTask, error: Throwable) {
                    onErrorCb?.let { cb ->
                        if (Looper.myLooper() == Looper.getMainLooper()) cb(error)
                        else mainHandler.post { cb(error) }
                    }
                }
            })
        }
        return task
    }
}
