package com.pichs.download.core

import androidx.lifecycle.LifecycleOwner
import android.os.Handler
import android.os.Looper
import com.pichs.download.config.Checksum
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

class DownloadRequestBuilder {
    private var url: String = ""
    private var path: String = ""
    private var fileName: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var checksum: Checksum? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var priority: Int = 1
    private var estimatedSize: Long = 0L
    private var extras: String? = null

    fun url(url: String) = apply { this.url = url }
    fun path(path: String) = apply { this.path = path }
    fun fileName(fileName: String) = apply { this.fileName = fileName }
    fun headers(headers: Map<String, String>) = apply { this.headers = headers }
    fun checksum(checksum: Checksum?) = apply { this.checksum = checksum }
    fun lifecycleOwner(lifecycleOwner: LifecycleOwner?) = apply { this.lifecycleOwner = lifecycleOwner }
    fun priority(priority: Int) = apply { this.priority = priority }
    fun estimatedSize(size: Long) = apply { this.estimatedSize = size }
    fun extras(extras: String?) = apply { this.extras = extras }

    fun start(): DownloadTask {
        val targetName = fileName ?: url.substringAfterLast('/').substringBefore('?')
        val normalized = normalizeName(targetName)
        
        // 检查是否已存在相同任务
        val existing = runBlocking { 
            DownloadManager.getAllTasks().firstOrNull {
                it.url == url && it.filePath == path && normalizeName(it.fileName) == normalized
            }
        }
        
        if (existing != null) {
            when (existing.status) {
                DownloadStatus.COMPLETED -> {
                    val file = File(existing.filePath, existing.fileName)
                    if (!file.exists()) {
                        // 文件被删除，删除任务记录并重新下载
                        DownloadManager.deleteTask(existing.id, deleteFile = false)
                        return createNewTask(targetName)
                    } else {
                        // 文件存在，返回现有任务
                        return existing
                    }
                }
                DownloadStatus.CANCELLED, DownloadStatus.FAILED -> {
                    // 删除失败/取消的任务记录，重新下载
                    DownloadManager.deleteTask(existing.id, deleteFile = false)
                    return createNewTask(targetName)
                }
                DownloadStatus.PAUSED -> {
                    // 直接恢复暂停的任务
                    DownloadManager.resume(existing.id)
                    return existing
                }
                else -> {
                    // 其他状态，返回现有任务
                    return existing
                }
            }
        }
        
        return createNewTask(targetName)
    }

    private fun createNewTask(targetName: String): DownloadTask {
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = targetName,
            filePath = path,
            totalSize = 0L,
            currentSize = 0L,
            progress = 0,
            speed = 0L,
            status = DownloadStatus.PENDING,
            priority = priority,
            createTime = System.currentTimeMillis(),
            updateTime = System.currentTimeMillis(),
            extras = extras,
            estimatedSize = estimatedSize
        )

        // 设置任务特定头部
        if (headers.isNotEmpty()) {
            DownloadManager.setTaskHeaders(task.id, headers)
        }

        // 创建任务
        DownloadManager.onTaskCreated(task)
        return task
    }

    private fun normalizeName(name: String): String = name.substringBeforeLast('.').lowercase()
}