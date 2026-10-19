package com.pichs.download.core

import androidx.lifecycle.LifecycleOwner
import android.os.Handler
import android.os.Looper
import com.pichs.download.config.Checksum
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.utils.FileUtils
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
    private var cellularConfirmed: Boolean = false

    fun url(url: String) = apply { this.url = url }
    fun path(path: String) = apply { this.path = path }
    fun fileName(fileName: String) = apply { this.fileName = fileName }
    fun headers(headers: Map<String, String>) = apply { this.headers = headers }
    fun checksum(checksum: Checksum?) = apply { this.checksum = checksum }
    fun lifecycleOwner(lifecycleOwner: LifecycleOwner?) = apply { this.lifecycleOwner = lifecycleOwner }
    fun priority(priority: Int) = apply { this.priority = priority }
    fun estimatedSize(size: Long) = apply { this.estimatedSize = size }
    fun extras(extras: String?) = apply { this.extras = extras }
    /** 标记此任务已确认使用流量下载（前置确认后设置） */
    fun cellularConfirmed(confirmed: Boolean) = apply { this.cellularConfirmed = confirmed }

    fun start(): DownloadTask {
        val targetName = resolveTargetName()
        val effectivePath = effectivePath()
        
        // 内存快照去重：恢复完成前也不会在主线程做全库扫描
        val existing = DownloadManager.findExistingTask(url, effectivePath, targetName)
        
        if (existing != null) {
            // 查找与使用之间任务可能已被删除，此时按新任务处理
            val latest = com.pichs.download.store.InMemoryTaskStore.get(existing.id)
                ?: return createNewTask(targetName)

            // 刷新请求头/鉴权信息后恢复，避免沿用已过期的 token
            if (headers.isNotEmpty()) {
                DownloadManager.setTaskHeaders(latest.id, headers)
            }
            checksum?.let { DownloadManager.setTaskChecksum(latest.id, it) }

            when (latest.status) {
                DownloadStatus.COMPLETED -> {
                    val file = File(latest.filePath, latest.fileName)
                    if (!file.exists()) {
                        // 文件被删除，删除任务记录并重新下载
                        DownloadManager.deleteTask(latest.id, deleteFile = false)
                        return createNewTask(targetName)
                    } else {
                        // 文件存在，返回现有任务
                        return latest
                    }
                }
                DownloadStatus.CANCELLED, DownloadStatus.FAILED -> {
                    // 删除失败/取消的任务记录，重新下载
                    DownloadManager.deleteTask(latest.id, deleteFile = false)
                    // 清理旧任务的断点文件，避免留下无人认领的孤儿文件
                    File(latest.filePath, "${latest.fileName}.${latest.id}.part")
                        .takeIf { it.exists() }?.delete()
                    File(latest.filePath, "${latest.fileName}.part")
                        .takeIf { it.exists() }?.delete()
                    return createNewTask(targetName)
                }
                DownloadStatus.PAUSED -> {
                    // 直接恢复暂停的任务
                    DownloadManager.resume(latest.id)
                    return com.pichs.download.store.InMemoryTaskStore.get(latest.id) ?: latest
                }
                else -> {
                    // 其他状态（含下载中/等待中），返回内存中的最新快照
                    return latest
                }
            }
        }
        
        return createNewTask(targetName)
    }
    
    /**
     * 只创建任务对象，不触发后续检查（供批量下载使用）
     */
    fun buildTask(): DownloadTask {
        require(url.isNotBlank()) { "[DownloadRequestBuilder] url is blank" }
        val targetName = resolveTargetName()
        val effectivePath = effectivePath()
        
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = targetName,
            filePath = effectivePath,
            totalSize = 0L,
            currentSize = 0L,
            progress = 0,
            speed = 0L,
            status = DownloadStatus.PENDING,
            priority = priority,
            createTime = com.pichs.download.utils.TimeUtils.currentMicros(),
            updateTime = com.pichs.download.utils.TimeUtils.currentMicros(),
            extras = extras,
            estimatedSize = estimatedSize,
            cellularConfirmed = cellularConfirmed
        )

        // 设置任务特定头部
        if (headers.isNotEmpty()) {
            DownloadManager.setTaskHeaders(task.id, headers)
        }
        applyChecksum(task.id, useConfigDefault = true)
        
        return task
    }

    private fun createNewTask(targetName: String): DownloadTask {
        val effectivePath = effectivePath()
        
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = targetName,
            filePath = effectivePath,
            totalSize = 0L,
            currentSize = 0L,
            progress = 0,
            speed = 0L,
            status = DownloadStatus.PENDING,
            priority = priority,
            createTime = com.pichs.download.utils.TimeUtils.currentMicros(),
            updateTime = com.pichs.download.utils.TimeUtils.currentMicros(),
            extras = extras,
            estimatedSize = estimatedSize,
            cellularConfirmed = cellularConfirmed
        )

        // 设置任务特定头部
        if (headers.isNotEmpty()) {
            DownloadManager.setTaskHeaders(task.id, headers)
        }
        applyChecksum(task.id, useConfigDefault = true)

        // 创建任务
        DownloadManager.checkAfterCreate(task)
        // checkAfterCreate 可能已经修改任务状态（如无网络时置为 PAUSED），返回内存中的最新值
        return com.pichs.download.store.InMemoryTaskStore.get(task.id) ?: task
    }

    private fun resolveTargetName(): String {
        return FileUtils.resolveFileName(
            url = url,
            fileName = fileName,
            resolver = DownloadManager.currentFileNameResolver()
        )
    }

    private fun normalizeName(name: String): String = name.substringBeforeLast('.').lowercase()

    private fun effectivePath(): String = FileUtils.canonicalPath(
        if (path.isBlank()) DownloadManager.getEffectiveDownloadDirPath() else path
    )

    private fun applyChecksum(taskId: String, useConfigDefault: Boolean) {
        val value = if (useConfigDefault) {
            checksum ?: DownloadManager.currentConfig().checksum
        } else {
            checksum
        }
        value?.let { DownloadManager.setTaskChecksum(taskId, it) }
    }

    internal fun resolveKey(): DownloadRequestKey {
        val targetName = resolveTargetName()
        return DownloadRequestKey(url, effectivePath(), normalizeName(targetName))
    }

    internal fun applyHeadersTo(taskId: String) {
        if (headers.isNotEmpty()) {
            DownloadManager.setTaskHeaders(taskId, headers)
        }
    }

    internal fun applyChecksumTo(taskId: String) {
        applyChecksum(taskId, useConfigDefault = false)
    }
}

internal data class DownloadRequestKey(
    val url: String,
    val path: String,
    val normalizedFileName: String
)