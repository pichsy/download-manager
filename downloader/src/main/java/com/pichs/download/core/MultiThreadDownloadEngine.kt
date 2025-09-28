package com.pichs.download.core

import com.pichs.download.model.ChunkStatus
import com.pichs.download.model.DownloadChunk
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.utils.FileUtils
import com.pichs.download.utils.OkHttpHelper
import com.pichs.download.utils.DownloadLog
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
    private val progressCalculator = ProgressCalculator()
    
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
        
        DownloadLog.d("MultiThreadDownloadEngine", "开始下载任务: ${task.id}, URL: ${task.url}")
        
        ctl.job = scope.launch {
            try {
                DownloadLog.d("MultiThreadDownloadEngine", "启动下载协程: ${task.id}")
                downloadWithChunks(task, ctl)
            } catch (e: CancellationException) {
                DownloadLog.d("MultiThreadDownloadEngine", "下载被取消: ${task.id}")
                // ignore
            } catch (e: Throwable) {
                DownloadLog.e("MultiThreadDownloadEngine", "下载失败: ${task.id}", e)
                val failed = task.copy(status = DownloadStatus.FAILED, updateTime = System.currentTimeMillis())
                DownloadManager.updateTaskInternal(failed)
            }
        }
    }
    
    override fun pause(taskId: String) {
        controllers[taskId]?.let { ctl ->
            ctl.paused.set(true)
            ctl.job?.cancel()
            runBlocking {
                // 直接从内存读取，避免异步缓存读取导致的旧值
                val task = DownloadManager.getTaskImmediate(taskId) ?: return@runBlocking
                // 如果任务已经是 PAUSED 且已有明确的 pauseReason（例如 NETWORK_ERROR/USER_MANUAL），不要覆盖
                if (task.status != DownloadStatus.PAUSED || task.pauseReason == null) {
                    val paused = task.copy(status = DownloadStatus.PAUSED, updateTime = System.currentTimeMillis())
                    DownloadManager.updateTaskInternal(paused)
                    DownloadLog.d("MultiThreadDownloadEngine", "引擎暂停任务: $taskId, 状态: ${paused.status}")
                } else {
                    DownloadLog.d("MultiThreadDownloadEngine", "任务已暂停，跳过状态更新: $taskId, 当前原因: ${task.pauseReason}")
                }
                // 清理进度计算器中的数据
                progressCalculator.clearTaskProgress(taskId)
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
                // 清理进度计算器中的数据
                progressCalculator.clearTaskProgress(taskId)
            }
        }
    }
    
    private suspend fun downloadWithChunks(task: DownloadTask, ctl: DownloadController) {
        DownloadLog.d("MultiThreadDownloadEngine", "开始下载分片: ${task.id}")
        
        val dir = File(task.filePath)
        withContext(Dispatchers.IO) { if (!dir.exists()) dir.mkdirs() }
        
        // 获取头信息
        DownloadLog.d("MultiThreadDownloadEngine", "获取文件头信息: ${task.id}")
        val header = withContext(Dispatchers.IO) { OkHttpHelper.getFileTotalLengthFromUrl(task.url) }
        ctl.total = header.contentLength
        ctl.eTag = header.eTag
        ctl.acceptRanges = header.acceptRanges
        ctl.lastModified = header.lastModified
        
        DownloadLog.d("MultiThreadDownloadEngine", "文件信息: ${task.id} - 总大小: ${header.contentLength}, ETag: ${header.eTag}, AcceptRanges: ${header.acceptRanges}")
        
        val finalName = FileUtils.generateFilename(task.fileName, task.url, header.contentType)
        val finalFile = File(dir, finalName)
        val tempFile = File(dir, "$finalName.part")
        ctl.finalFile = finalFile
        ctl.tempFile = tempFile
        
        // 已完成直接回调
        if (finalFile.exists() && finalFile.length() == header.contentLength && header.contentLength > 0) {
            DownloadLog.d("MultiThreadDownloadEngine", "文件已存在，直接完成: ${task.id} - 文件大小: ${finalFile.length()}")
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
            DownloadManager.emitProgress(completed, 100, 0)
            return
        }
        
        // 创建或恢复分片
        val chunks = ctl.chunkManager?.getChunks(task.id) ?: emptyList()
        ctl.chunks = if (chunks.isEmpty()) {
            val chunkCount = calculateOptimalThreadCount(header.contentLength)
            DownloadLog.d("MultiThreadDownloadEngine", "创建分片: ${task.id} - 分片数量: $chunkCount")
            ctl.chunkManager?.createChunks(task.id, header.contentLength, chunkCount) ?: emptyList()
        } else {
            DownloadLog.d("MultiThreadDownloadEngine", "恢复分片: ${task.id} - 分片数量: ${chunks.size}")
            chunks
        }
        
        // 准备临时文件
        withContext(Dispatchers.IO) { FileUtils.checkAndCreateFileSafe(tempFile) }
        
        // 并发下载分片
        DownloadLog.d("MultiThreadDownloadEngine", "开始并发下载: ${task.id} - 分片数量: ${ctl.chunks.size}")
        val downloadJobs = ctl.chunks.map { chunk ->
            scope.launch {
                DownloadLog.d("MultiThreadDownloadEngine", "启动分片下载: ${task.id} - 分片: ${chunk.index}")
                downloadChunk(task, chunk, ctl)
            }
        }
        
        // 等待所有分片完成
        DownloadLog.d("MultiThreadDownloadEngine", "等待所有分片完成: ${task.id}")
        downloadJobs.joinAll()
        DownloadLog.d("MultiThreadDownloadEngine", "所有分片下载完成: ${task.id}")
        
        // 检查任务是否被取消、暂停或网络异常暂停
        if (ctl.cancelled.get() || ctl.paused.get()) {
            DownloadLog.d("MultiThreadDownloadEngine", "任务被取消或暂停，跳过文件合并: ${task.id}")
            return
        }
        
        // 检查任务是否因为网络异常被暂停
        val currentTask = runBlocking { DownloadManager.getTask(task.id) }
        if (currentTask?.status == DownloadStatus.PAUSED && currentTask.pauseReason == com.pichs.download.model.PauseReason.NETWORK_ERROR) {
            DownloadLog.d("MultiThreadDownloadEngine", "任务因网络异常暂停，跳过文件合并: ${task.id}")
            return
        }
        
        // 合并文件
        DownloadLog.d("MultiThreadDownloadEngine", "开始合并文件: ${task.id}")
        val success = withContext(Dispatchers.IO) { FileUtils.rename(tempFile, finalFile) }
        val now = System.currentTimeMillis()
        if (success) {
            DownloadLog.d("MultiThreadDownloadEngine", "文件合并成功: ${task.id}")
            val completed = progressCalculator.getFinalProgress(
                task = task,
                chunks = ctl.chunks,
                totalSize = ctl.total
            ).copy(fileName = finalName)
            
            DownloadManager.updateTaskInternal(completed)
            DownloadManager.emitProgress(completed, 100, 0)
            
            // 清理进度计算器中的数据
            ProgressCalculatorManager.clearCalculator(task.id)
        } else {
            DownloadLog.e("MultiThreadDownloadEngine", "文件合并失败: ${task.id}")
            val failed = task.copy(status = DownloadStatus.FAILED, updateTime = now)
            DownloadManager.updateTaskInternal(failed)
            
            // 清理进度计算器中的数据
            ProgressCalculatorManager.clearCalculator(task.id)
        }
    }
    
    private suspend fun downloadChunk(task: DownloadTask, chunk: DownloadChunk, ctl: DownloadController) {
        try {
            if (chunk.status == ChunkStatus.COMPLETED) {
                DownloadLog.d("MultiThreadDownloadEngine", "分片已完成，跳过: ${task.id} - 分片: ${chunk.index}")
                return
            }
            
            DownloadLog.d("MultiThreadDownloadEngine", "开始下载分片: ${task.id} - 分片: ${chunk.index}, 范围: ${chunk.startByte}-${chunk.endByte}")
            
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
        
        DownloadLog.d("MultiThreadDownloadEngine", "发送分片请求: ${task.id} - 分片: ${chunk.index}, Range: bytes=$startByte-$endByte")
        
        // 进度计算现在由ProgressCalculator处理
        
        OkHttpHelper.execute(request).use { resp ->
            val code = resp.code
            val body = resp.body ?: throw IllegalStateException("Empty body")
            
            DownloadLog.d("MultiThreadDownloadEngine", "分片响应: ${task.id} - 分片: ${chunk.index}, 状态码: $code, 内容长度: ${body.contentLength()}")
            
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
            
            // 维护本地累计下载量，避免依赖数据库中的旧值
            var localDownloaded = chunk.downloaded
            
            RandomAccessFile(ctl.tempFile, "rw").use { raf ->
                raf.seek(startByte)
                val source = body.source()
                val buffer = okio.Buffer()
                
                while (true) {
                    if (ctl.paused.get() || ctl.cancelled.get()) break
                    val read = source.read(buffer, 8 * 1024)
                    if (read == -1L) break
                    raf.channel.write(buffer.readByteArray(read).let { java.nio.ByteBuffer.wrap(it) })
                    
                    // 更新本地累计值
                    localDownloaded += read
                    ctl.chunkManager?.updateChunkProgress(task.id, chunk.index, localDownloaded, ChunkStatus.DOWNLOADING)
                    
                    // 使用ProgressCalculatorManager获取该任务的专用计算器
                    // 实时获取最新分片数据，而不是使用静态的ctl.chunks
                    val latestChunks = ctl.chunkManager?.getChunks(task.id) ?: emptyList()
                    val calculator = ProgressCalculatorManager.getCalculator(task.id)
                    val (shouldUpdate, updatedTask) = calculator.calculateProgress(
                        task = task,
                        chunks = latestChunks,
                        totalSize = ctl.total,
                        minUpdateInterval = 500L
                    )
                    
                    if (shouldUpdate) {
                        val finalTask = updatedTask.copy(
                            fileName = ctl.finalFile?.name ?: task.fileName
                        )
                        DownloadManager.updateTaskInternal(finalTask)
                        DownloadManager.emitProgress(finalTask, finalTask.progress, finalTask.speed)
                        
                        // 添加详细的下载日志
                        DownloadLog.d("MultiThreadDownloadEngine", 
                            "进度更新: ${task.id} - 进度: ${finalTask.progress}%, 速度: ${finalTask.speed}bytes/s, 已下载: ${finalTask.currentSize}/${finalTask.totalSize}")
                    }
                }
            }
            
            if (!ctl.cancelled.get() && !ctl.paused.get()) {
                DownloadLog.d("MultiThreadDownloadEngine", "分片下载完成: ${task.id} - 分片: ${chunk.index}, 已下载: $localDownloaded")
                ctl.chunkManager?.updateChunkProgress(task.id, chunk.index, localDownloaded, ChunkStatus.COMPLETED)
            } else {
                DownloadLog.d("MultiThreadDownloadEngine", "分片下载被取消或暂停: ${task.id} - 分片: ${chunk.index}")
            }
        }
        } catch (e: Exception) {
            DownloadLog.e("MultiThreadDownloadEngine", "分片下载异常: ${task.id} - 分片: ${chunk.index}", e)
            
            // 根据异常类型决定任务状态
            val pauseReason = com.pichs.download.utils.ErrorClassifier.getPauseReason(e)
            if (pauseReason != null) {
                // 网络异常或存储异常，暂停任务并停止所有分片
                // 使用最新的分片数据获取准确的当前进度
                val latestChunks = runBlocking {
                    ctl.chunkManager?.getChunks(task.id)
                } ?: ctl.chunks
                ctl.chunks = latestChunks

                val effectiveTotal = if (ctl.total > 0) ctl.total else task.totalSize
                val progressCalculator = com.pichs.download.core.ProgressCalculatorManager.getCalculator(task.id)
                val currentProgress = progressCalculator.getCurrentProgress(task.id, latestChunks, effectiveTotal)
                val currentSize = latestChunks.sumOf { it.downloaded }
                
                val pausedTask = task.copy(
                    status = DownloadStatus.PAUSED,
                    pauseReason = pauseReason,
                    progress = currentProgress,
                    totalSize = if (effectiveTotal > 0) effectiveTotal else task.totalSize,
                    currentSize = currentSize,
                    speed = 0L, // 暂停时速度归零
                    updateTime = System.currentTimeMillis()
                )
                DownloadManager.updateTaskInternal(pausedTask)
                
                // 立即停止所有分片下载
                ctl.paused.set(true)
                DownloadLog.d("MultiThreadDownloadEngine", 
                    "任务因 ${pauseReason} 暂停，停止所有分片: ${task.id}, 当前进度: ${currentProgress}%")
            } else {
                // 其他异常，标记分片失败
                ctl.chunkManager?.updateChunkProgress(task.id, chunk.index, chunk.downloaded, ChunkStatus.FAILED)
            }
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
