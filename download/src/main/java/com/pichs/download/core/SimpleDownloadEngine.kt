package com.pichs.download.core

import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.utils.DownloadLog
import com.pichs.download.utils.FileUtils
import com.pichs.download.utils.OkHttpHelper
import kotlinx.coroutines.*
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

internal class SimpleDownloadEngine : DownloadEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Controller(
        var job: Job? = null,
        var paused: Boolean = false,
        var cancelled: Boolean = false,
        var total: Long = -1L,
        var eTag: String? = null,
        var acceptRanges: String? = null,
        var tempFile: File? = null,
        var finalFile: File? = null,
    )

    private val controllers = ConcurrentHashMap<String, Controller>()

    override suspend fun start(task: DownloadTask) {
        val ctl = controllers.getOrPut(task.id) { Controller() }
        ctl.paused = false
        ctl.cancelled = false

        ctl.job = scope.launch {
            try {
                downloadInternal(task, ctl)
            } catch (e: CancellationException) {
                // ignore
            } catch (e: Throwable) {
                val failed = task.copy(status = DownloadStatus.FAILED, updateTime = System.currentTimeMillis())
                DownloadManager.updateTaskInternal(failed)
                DownloadManager.listeners().notifyTaskError(failed, e)
            }
        }
    }

    override fun pause(taskId: String) {
        controllers[taskId]?.let { ctl ->
            ctl.paused = true
            ctl.job?.cancel()
            val task = DownloadManager.getTask(taskId) ?: return
            val paused = task.copy(status = DownloadStatus.PAUSED, updateTime = System.currentTimeMillis())
            DownloadManager.updateTaskInternal(paused)
            DownloadManager.listeners().notifyTaskProgress(paused, paused.progress, paused.speed)
        }
    }

    override fun resume(taskId: String) {
        val task = DownloadManager.getTask(taskId) ?: return
        controllers[taskId]?.paused = false
        scope.launch { start(task.copy(status = DownloadStatus.PENDING)) }
    }

    override fun cancel(taskId: String) {
        controllers[taskId]?.let { ctl ->
            ctl.cancelled = true
            ctl.job?.cancel()
            ctl.tempFile?.let { FileUtils.deleteFile(it.absolutePath) }
            val task = DownloadManager.getTask(taskId) ?: return
            val cancelled = task.copy(status = DownloadStatus.CANCELLED, updateTime = System.currentTimeMillis())
            DownloadManager.updateTaskInternal(cancelled)
            // 派发取消作为状态变化，这里不单独提供回调，后续可拓展
        }
    }

    private suspend fun downloadInternal(task: DownloadTask, ctl: Controller) {
        val dir = File(task.filePath)
        withContext(Dispatchers.IO) { if (!dir.exists()) dir.mkdirs() }

        // 获取头信息
        val header = withContext(Dispatchers.IO) { OkHttpHelper.getFileTotalLengthFromUrl(task.url) }
        ctl.total = header.contentLength
        ctl.eTag = header.eTag
        ctl.acceptRanges = header.acceptRanges

        val finalName = FileUtils.generateFilename(task.fileName, task.url, header.contentType)
        val finalFile = File(dir, finalName)
        val tempFile = File(dir, "$finalName.part")
        ctl.finalFile = finalFile
        ctl.tempFile = tempFile

        // 已完成直接回调
        if (finalFile.exists() && finalFile.length() == header.contentLength && header.contentLength > 0) {
            val completed = task.copy(
                fileName = finalName,
                status = DownloadStatus.COMPLETED,
                progress = 100,
                totalSize = header.contentLength,
                currentSize = header.contentLength,
                speed = 0,
                updateTime = System.currentTimeMillis()
            )
            DownloadManager.updateTaskInternal(completed)
            DownloadManager.listeners().notifyTaskComplete(completed, finalFile)
            return
        }

        // 计算断点
        var downloaded: Long = when {
            tempFile.exists() -> tempFile.length()
            else -> 0L
        }
        downloaded = max(0L, downloaded)

        // 准备请求
        val reqBuilder = Request.Builder().url(task.url)
        if (downloaded > 0) reqBuilder.header("Range", "bytes=$downloaded-")
        val request = reqBuilder.get().build()

        val startTime = System.currentTimeMillis()
        var lastUpdateTime = startTime
        var lastBytes = downloaded

        // 确保临时文件存在
        withContext(Dispatchers.IO) { FileUtils.checkAndCreateFileSafe(tempFile) }

        OkHttpHelper.execute(request).use { resp ->
            if (!(resp.isSuccessful || resp.code == 206 || resp.code == 200)) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("Empty body")

            // 如果服务器不支持 Range 且返回 200，则重下
            if (resp.code == 200 && downloaded > 0L) {
                withContext(Dispatchers.IO) { FileUtils.deleteFile(tempFile.absolutePath) }
                downloaded = 0L
            }

            val total = if (header.contentLength > 0) header.contentLength else (downloaded + body.contentLength())

            // 写文件（追加）
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.seek(downloaded)
                val source = body.source()
                val buffer = okio.Buffer()
                while (true) {
                    if (ctl.paused || ctl.cancelled) break
                    val read = source.read(buffer, 8 * 1024)
                    if (read == -1L) break
                    raf.channel.write(buffer.readByteArray(read).let { java.nio.ByteBuffer.wrap(it) })
                    downloaded += read

                    val now = System.currentTimeMillis()
                    val deltaT = now - lastUpdateTime
                    if (deltaT >= 200) {
                        val deltaB = downloaded - lastBytes
                        val speed = if (deltaT > 0) (deltaB * 1000 / deltaT) else 0
                        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                        val running = task.copy(
                            status = DownloadStatus.DOWNLOADING,
                            progress = progress,
                            totalSize = total,
                            currentSize = downloaded,
                            speed = speed,
                            fileName = finalName,
                            updateTime = now
                        )
                        DownloadManager.updateTaskInternal(running)
                        DownloadManager.listeners().notifyTaskProgress(running, progress, speed)
                        lastUpdateTime = now
                        lastBytes = downloaded
                    }
                }
            }
        }

        if (ctl.cancelled) return
        if (ctl.paused) return

        // 完成：重命名
        val success = withContext(Dispatchers.IO) { FileUtils.rename(tempFile, finalFile) }
        val now = System.currentTimeMillis()
        if (success) {
            val completed = task.copy(
                status = DownloadStatus.COMPLETED,
                progress = 100,
                totalSize = ctl.total,
                currentSize = ctl.total,
                speed = 0,
                fileName = finalName,
                updateTime = now
            )
            DownloadManager.updateTaskInternal(completed)
            DownloadManager.listeners().notifyTaskComplete(completed, finalFile)
        } else {
            val failed = task.copy(status = DownloadStatus.FAILED, updateTime = now)
            DownloadManager.updateTaskInternal(failed)
            DownloadManager.listeners().notifyTaskError(failed, IllegalStateException("Rename failed"))
        }
    }
}
