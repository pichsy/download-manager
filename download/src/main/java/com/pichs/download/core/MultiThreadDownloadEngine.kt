package com.pichs.download.core

import com.pichs.download.model.ChunkStatus
import com.pichs.download.model.DownloadChunk
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.utils.FileUtils
import com.pichs.download.utils.OkHttpHelper
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal class MultiThreadDownloadEngine : DownloadEngine {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controllers = ConcurrentHashMap<String, DownloadController>()
    
    private data class DownloadController(
        var job: Job? = null,
        var paused: AtomicBoolean = AtomicBoolean(false),
        var cancelled: AtomicBoolean = AtomicBoolean(false),
        var total: Long = -1L,
        var eTag: String? = null,
        var acceptRanges: String? = null,
        var lastModified: String? = null,
        var tempFile: File? = null,
        var finalFile: File? = null,
        var chunks: List<DownloadChunk> = emptyList(),
        var chunkManager: ChunkManager? = null
    )
    
    override suspend fun start(task: DownloadTask) {
        val ctl = controllers.getOrPut(task.id) { DownloadController() }
        ctl.paused.set(false)
        ctl.cancelled.set(false)
        ctl.chunkManager = DownloadManager.getChunkManager()
        
        ctl.job = scope.launch {
            try {
                downloadWithChunks(task, ctl)
            } catch (e: CancellationException) {
                // ignore
            } catch (e: Throwable) {
                val failed = task.copy(status = DownloadStatus.FAILED, updateTime = System.currentTimeMillis())
                DownloadManager.updateTaskInternal(failed)
                // 旧监听器已移除，现在通过EventBus和Flow通知
            }
        }
    }
    
    override fun pause(taskId: String) {
        controllers[taskId]?.let { ctl ->
            ctl.paused.set(true)
            ctl.job?.cancel()
            runBlocking {
                val task = DownloadManager.getTask(taskId) ?: return@runBlocking
                val paused = task.copy(status = DownloadStatus.PAUSED, updateTime = System.currentTimeMillis())
                DownloadManager.updateTaskInternal(paused)
                // 旧监听器已移除，现在通过EventBus和Flow通知
            }
        }
    }
    
    override fun resume(taskId: String) {
        runBlocking {
            val task = DownloadManager.getTask(taskId) ?: return@runBlocking
            controllers[taskId]?.paused?.set(false)
        }
        // 交由调度器在 DownloadManager.resume 中统一置为 WAITING/PENDING
    }
    
    override fun cancel(taskId: String) {
        controllers[taskId]?.let { ctl ->
            ctl.cancelled.set(true)
            ctl.job?.cancel()
            ctl.tempFile?.let { FileUtils.deleteFile(it.absolutePath) }
            runBlocking {
                val task = DownloadManager.getTask(taskId) ?: return@runBlocking
                val cancelled = task.copy(status = DownloadStatus.CANCELLED, updateTime = System.currentTimeMillis())
                DownloadManager.updateTaskInternal(cancelled)
            }
        }
    }
    
    private suspend fun downloadWithChunks(task: DownloadTask, ctl: DownloadController) {
        val dir = File(task.filePath)
        withContext(Dispatchers.IO) { if (!dir.exists()) dir.mkdirs() }
        
        // 获取头信息
        val header = withContext(Dispatchers.IO) { OkHttpHelper.getFileTotalLengthFromUrl(task.url) }
        ctl.total = header.contentLength
        ctl.eTag = header.eTag
        ctl.acceptRanges = header.acceptRanges
        ctl.lastModified = header.lastModified
        
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
            // 旧监听器已移除，现在通过EventBus和Flow通知
            DownloadManager.emitProgress(completed, 100, 0)
            return
        }
        
        // 创建或恢复分片
        val chunks = ctl.chunkManager?.getChunks(task.id) ?: emptyList()
        ctl.chunks = if (chunks.isEmpty()) {
            val chunkCount = calculateOptimalThreadCount(header.contentLength)
            ctl.chunkManager?.createChunks(task.id, header.contentLength, chunkCount) ?: emptyList()
        } else {
            chunks
        }
        
        // 准备临时文件
        withContext(Dispatchers.IO) { FileUtils.checkAndCreateFileSafe(tempFile) }
        
        // 并发下载分片
        val downloadJobs = ctl.chunks.map { chunk ->
            scope.launch {
                downloadChunk(task, chunk, ctl)
            }
        }
        
        // 等待所有分片完成
        downloadJobs.joinAll()
        
        if (ctl.cancelled.get() || ctl.paused.get()) return
        
        // 合并文件
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
            // 旧监听器已移除，现在通过EventBus和Flow通知
            DownloadManager.emitProgress(completed, 100, 0)
        } else {
            val failed = task.copy(status = DownloadStatus.FAILED, updateTime = now)
            DownloadManager.updateTaskInternal(failed)
            // 旧监听器已移除，现在通过EventBus和Flow通知
        }
    }
    
    private suspend fun downloadChunk(task: DownloadTask, chunk: DownloadChunk, ctl: DownloadController) {
        if (chunk.status == ChunkStatus.COMPLETED) return
        
        val startByte = chunk.startByte + chunk.downloaded
        val endByte = chunk.endByte
        
        val reqBuilder = Request.Builder().url(task.url)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=$startByte-$endByte")
        
        // 透传任务级请求头
        DownloadManager.getTaskHeaders(task.id).forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true)) {
                reqBuilder.header(k, v)
            }
        }
        
        // If-Range: 优先 ETag，其次 Last-Modified
        ctl.eTag?.let { reqBuilder.header("If-Range", it) }
            ?: ctl.lastModified?.let { reqBuilder.header("If-Range", it) }
        
        val request = reqBuilder.get().build()
        
        var lastUpdateTime = System.currentTimeMillis()
        var lastBytes = chunk.downloaded
        
        OkHttpHelper.execute(request).use { resp ->
            val code = resp.code
            val body = resp.body ?: throw IllegalStateException("Empty body")
            
            // 处理 416: 请求区间无效，通常是文件大小变化或断点越界 -> 重下
            if (code == 416) {
                ctl.chunkManager?.updateChunkProgress(task.id, chunk.index, 0, ChunkStatus.PENDING)
                return
            }
            
            // 若返回 200 且有断点，说明 If-Range 校验不通过或不支持 Range -> 全量重下
            if (code == 200 && chunk.downloaded > 0L) {
                ctl.chunkManager?.updateChunkProgress(task.id, chunk.index, 0, ChunkStatus.PENDING)
                return
            }
            
            RandomAccessFile(ctl.tempFile, "rw").use { raf ->
                raf.seek(startByte)
                val source = body.source()
                val buffer = okio.Buffer()
                
                while (true) {
                    if (ctl.paused.get() || ctl.cancelled.get()) break
                    val read = source.read(buffer, 8 * 1024)
                    if (read == -1L) break
                    raf.channel.write(buffer.readByteArray(read).let { java.nio.ByteBuffer.wrap(it) })
                    
                    val newDownloaded = chunk.downloaded + read
                    ctl.chunkManager?.updateChunkProgress(task.id, chunk.index, newDownloaded, ChunkStatus.DOWNLOADING)
                    
                    val now = System.currentTimeMillis()
                    val deltaT = now - lastUpdateTime
                    if (deltaT >= 200) {
                        val deltaB = newDownloaded - lastBytes
                        val speed = if (deltaT > 0) (deltaB * 1000 / deltaT) else 0
                        
                        // 计算总进度
                        val totalDownloaded = ctl.chunks.sumOf { it.downloaded }
                        val progress = if (ctl.total > 0) ((totalDownloaded * 100) / ctl.total).toInt() else 0
                        
                        val running = task.copy(
                            status = DownloadStatus.DOWNLOADING,
                            progress = progress,
                            totalSize = ctl.total,
                            currentSize = totalDownloaded,
                            speed = speed,
                            fileName = ctl.finalFile?.name ?: task.fileName,
                            updateTime = now
                        )
                        DownloadManager.updateTaskInternal(running)
                        // 旧监听器已移除，现在通过EventBus和Flow通知
                        DownloadManager.emitProgress(running, progress, speed)
                        
                        lastUpdateTime = now
                        lastBytes = newDownloaded
                    }
                }
            }
        }
        
        if (!ctl.cancelled.get() && !ctl.paused.get()) {
            ctl.chunkManager?.updateChunkProgress(task.id, chunk.index, chunk.endByte - chunk.startByte + 1, ChunkStatus.COMPLETED)
        }
    }
    
    private fun calculateOptimalThreadCount(fileSize: Long): Int {
        return when {
            fileSize < 1024 * 1024 -> 1      // 1MB以下单线程
            fileSize < 10 * 1024 * 1024 -> 2 // 10MB以下2线程
            fileSize < 100 * 1024 * 1024 -> 3 // 100MB以下3线程
            else -> 4                         // 100MB以上4线程
        }
    }
}
