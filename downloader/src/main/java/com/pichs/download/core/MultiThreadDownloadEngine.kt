package com.pichs.download.core

import com.pichs.download.config.Checksum
import com.pichs.download.model.ChunkStatus
import com.pichs.download.model.DownloadChunk
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.store.InMemoryTaskStore
import com.pichs.download.utils.ChecksumVerifier
import com.pichs.download.utils.DownloadLog
import com.pichs.download.utils.ErrorClassifier
import com.pichs.download.utils.FileUtils
import com.pichs.download.utils.OkHttpHelper
import com.pichs.download.utils.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

internal class MultiThreadDownloadEngine : DownloadEngine {

    companion object {
        private const val TAG = "MultiThreadDownloadEngine"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PERSIST_INTERVAL_MS = 500L
        private const val PERSIST_BYTES = 1024L * 1024L
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val PREALLOCATE_THRESHOLD = 16L * 1024L * 1024L
        private const val MAX_RETRIES = 3
        private const val MAX_CONTINUATIONS = 1000
        private val RETRY_DELAYS_MS = longArrayOf(500L, 1000L, 2000L)

        // 探测请求占用一个不会与分片索引冲突的 key
        private const val PROBE_CALL_KEY = Int.MIN_VALUE

        // 最终文件名占位：同一路径只允许一个任务使用，避免并发解析出相同的 (1) 后缀
        private val finalNameRegistry = FinalNameRegistry()

        internal fun releaseFinalNames(taskId: String) {
            finalNameRegistry.release(taskId)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controllers = ConcurrentHashMap<String, DownloadController>()
    private val controllerLock = Any()

    private class DownloadController {
        @Volatile var job: Job? = null
        val paused = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        @Volatile var total: Long = -1L
        @Volatile var eTag: String? = null
        @Volatile var acceptRanges: String? = null
        @Volatile var lastModified: String? = null
        @Volatile var tempFile: File? = null
        @Volatile var finalFile: File? = null
        @Volatile var chunks: List<DownloadChunk> = emptyList()
        @Volatile var chunkManager: ChunkManager? = null
        val chunkProgress = ConcurrentHashMap<Int, Long>()
        val activeCalls = ConcurrentHashMap<Int, Call>()
    }

    override suspend fun start(task: DownloadTask) {
        val initial = DownloadManager.getTaskImmediate(task.id) ?: run {
            DownloadLog.d(TAG, "任务已删除，跳过启动: ${task.id}")
            return
        }
        if (initial.status == DownloadStatus.PAUSED || initial.status == DownloadStatus.CANCELLED) {
            DownloadLog.d(TAG, "任务已停止，跳过启动: ${task.id}")
            return
        }

        val validatedTask = validateBeforeDownload(task)
        val selfJob = currentCoroutineContext()[Job]
        val ctl: DownloadController
        synchronized(controllerLock) {
            val existing = controllers[validatedTask.id]
            if (existing?.job?.isActive == true) {
                DownloadLog.w(TAG, "任务已有活跃下载，忽略重复启动: ${validatedTask.id}")
                return
            }
            ctl = DownloadController()
            ctl.chunkManager = DownloadManager.getChunkManager()
            ctl.job = selfJob
            controllers[validatedTask.id] = ctl
        }

        DownloadLog.d(TAG, "开始下载任务: ${validatedTask.id}, URL: ${validatedTask.url}")

        try {
            downloadWithChunks(validatedTask, ctl)
        } catch (_: CancellationException) {
            DownloadLog.d(TAG, "下载协程已取消: ${validatedTask.id}")
        } catch (e: Throwable) {
            if (ctl.paused.get() || ctl.cancelled.get() || !currentCoroutineContext().isActive) {
                DownloadLog.d(TAG, "任务已暂停/取消，忽略异常: ${e.message}")
            } else {
                handleFailure(validatedTask, ctl, e)
            }
        } finally {
            ctl.activeCalls.values.forEach { it.cancel() }
            ctl.activeCalls.clear()
            synchronized(controllerLock) {
                if (controllers[validatedTask.id] === ctl) {
                    controllers.remove(validatedTask.id)
                }
            }
            ProgressCalculatorManager.clearCalculator(validatedTask.id)
            DownloadLog.d(TAG, "清理任务资源: ${validatedTask.id}")
        }
    }

    override fun pause(taskId: String) {
        stopController(taskId)
    }

    override fun resume(taskId: String) {
        controllers[taskId]?.paused?.set(false)
    }

    override fun preempt(taskId: String) {
        stopController(taskId)
        val current = DownloadManager.getTaskImmediate(taskId) ?: return
        if (current.status == DownloadStatus.DOWNLOADING ||
            current.status == DownloadStatus.WAITING ||
            current.status == DownloadStatus.PENDING
        ) {
            val waiting = current.copy(
                status = DownloadStatus.WAITING,
                pauseReason = null,
                speed = 0L,
                updateTime = TimeUtils.currentMicros()
            )
            DownloadManager.updateTaskInternal(waiting)
            DownloadLog.d(TAG, "任务被抢占，改为等待中: $taskId")
        }
    }

    override fun cancel(taskId: String) {
        stopController(taskId)
    }

    private fun stopController(taskId: String) {
        val ctl = controllers[taskId] ?: return
        ctl.paused.set(true)
        ctl.activeCalls.values.forEach { it.cancel() }
        ctl.job?.cancel()
        ctl.activeCalls.clear()
        ProgressCalculatorManager.clearCalculator(taskId)
        DownloadLog.d(TAG, "停止任务下载: $taskId")
    }

    /**
     * 下载前文件验证和修复
     */
    private suspend fun validateBeforeDownload(task: DownloadTask): DownloadTask {
        val safeName = FileUtils.sanitizeFileName(task.fileName)
        val file = File(task.filePath, safeName)
        val partFile = File(task.filePath, "$safeName.part")
        val qualifiedPartFile = File(task.filePath, "$safeName.${task.id}.part")

        if (task.currentSize > 0 && !file.exists() && !partFile.exists() && !qualifiedPartFile.exists()) {
            DownloadLog.w(TAG, "检测到断点续传文件丢失，自动重置进度: ${task.fileName}")
            return resetTask(task)
        }

        if (file.exists() && task.totalSize > 0 && file.length() > task.totalSize) {
            DownloadLog.w(TAG, "检测到文件损坏，删除并重新下载: ${task.fileName}")
            withContext(Dispatchers.IO) { file.delete() }
            return resetTask(task)
        }

        if (partFile.exists() && task.totalSize > 0 && partFile.length() > task.totalSize) {
            DownloadLog.w(TAG, "检测到临时文件损坏，删除并重新下载: ${task.fileName}.part")
            withContext(Dispatchers.IO) { partFile.delete() }
            return resetTask(task)
        }

        if (qualifiedPartFile.exists() && task.totalSize > 0 && qualifiedPartFile.length() > task.totalSize) {
            DownloadLog.w(TAG, "检测到临时文件损坏，删除并重新下载: ${qualifiedPartFile.name}")
            withContext(Dispatchers.IO) { qualifiedPartFile.delete() }
            return resetTask(task)
        }

        return task
    }

    private suspend fun resetTask(task: DownloadTask): DownloadTask {
        val resetTask = task.copy(
            currentSize = 0,
            progress = 0,
            totalSize = 0,
            speed = 0,
            updateTime = TimeUtils.currentMicros()
        )
        DownloadManager.updateTaskInternal(resetTask)
        DownloadManager.getChunkManager()?.deleteChunks(task.id)
        return resetTask
    }

    private suspend fun downloadWithChunks(task: DownloadTask, ctl: DownloadController) {
        val dir = File(FileUtils.canonicalPath(task.filePath))
        withContext(Dispatchers.IO) { if (!dir.exists()) dir.mkdirs() }

        DownloadLog.d(TAG, "获取文件头信息: ${task.id}")
        val storedMeta = DownloadManager.getTaskMeta(task.id)
        val header = withContext(Dispatchers.IO) {
            try {
                OkHttpHelper.getFileTotalLengthFromUrl(
                    task.url,
                    DownloadManager.getTaskHeaders(task.id)
                ) { call ->
                    if (ctl.paused.get() || ctl.cancelled.get() ||
                        DownloadManager.getTaskImmediate(task.id) == null
                    ) {
                        call.cancel()
                    }
                    ctl.activeCalls[PROBE_CALL_KEY] = call
                }
            } finally {
                ctl.activeCalls.remove(PROBE_CALL_KEY)
            }
        }
        checkActive(ctl)

        ctl.total = header.contentLength
        ctl.eTag = header.eTag
        ctl.acceptRanges = header.acceptRanges
        ctl.lastModified = header.lastModified

        DownloadLog.d(
            TAG,
            "文件信息: ${task.id} - 大小=${header.contentLength}, ETag=${header.eTag}, AcceptRanges=${header.acceptRanges}"
        )

        // 远端文件发生变化（校验器或大小不同）时，旧断点数据不可再使用
        val metaChanged = storedMeta != null && (
                (storedMeta.eTag != null && header.eTag != null && storedMeta.eTag != header.eTag) ||
                        (storedMeta.lastModified != null && header.lastModified != null &&
                                storedMeta.lastModified != header.lastModified) ||
                        (storedMeta.totalSize > 0 && header.contentLength > 0 &&
                                storedMeta.totalSize != header.contentLength)
                )

        val generatedName = FileUtils.generateFilename(task.fileName, task.url, header.contentType)
        val finalName = resolveFinalFileName(dir, generatedName, task.id, task.currentSize > 0)
        val finalFile = File(dir, finalName)
        val tempFile = File(dir, "$finalName.${task.id}.part")
        ctl.finalFile = finalFile
        ctl.tempFile = tempFile

        // 旧版本临时文件不带任务 ID，首次升级时迁移以保留断点
        val legacyTemp = File(dir, "$finalName.part")
        if (!tempFile.exists() && legacyTemp.exists()) {
            withContext(Dispatchers.IO) {
                if (!FileUtils.rename(legacyTemp, tempFile)) {
                    DownloadLog.w(TAG, "旧临时文件迁移失败: ${legacyTemp.absolutePath}")
                }
            }
        }

        if (metaChanged) {
            DownloadLog.w(TAG, "远端文件已变化，丢弃旧断点数据: ${task.id}")
            withContext(Dispatchers.IO) {
                if (tempFile.exists()) tempFile.delete()
                if (legacyTemp.exists()) legacyTemp.delete()
            }
            ctl.chunkManager?.deleteChunks(task.id)
            ctl.chunkProgress.clear()
            val current = DownloadManager.getTaskImmediate(task.id)
            if (current != null &&
                current.status != DownloadStatus.CANCELLED &&
                (current.currentSize > 0 || current.totalSize > 0)
            ) {
                DownloadManager.updateTaskInternal(
                    current.copy(
                        currentSize = 0,
                        progress = 0,
                        speed = 0L,
                        updateTime = TimeUtils.currentMicros()
                    )
                )
            }
        }
        DownloadManager.updateTaskMeta(task.id, header.eTag, header.lastModified, header.contentLength)

        // 远端内容变化后旧文件不可信，必须重新下载
        if (!metaChanged && finalFile.exists() && ctl.total >= 0 && finalFile.length() == ctl.total) {
            // 校验不通过时（Retry 策略）删除旧文件并继续走真实下载
            if (verifyFileChecksum(task, finalFile, allowChecksumRetry = true)) {
                DownloadLog.d(TAG, "文件已存在且大小一致，直接完成: ${task.id}")
                withContext(Dispatchers.IO) {
                    if (tempFile.exists()) tempFile.delete()
                    if (legacyTemp.exists()) legacyTemp.delete()
                }
                markCompletedDirectly(task, finalName, ctl.total)
                return
            }
            DownloadLog.w(TAG, "已存在文件校验失败，重新下载: ${task.id}")
        }

        if (ctl.total == 0L) {
            DownloadLog.d(TAG, "远端文件长度为 0，创建空文件: ${task.id}")
            withContext(Dispatchers.IO) {
                if (finalFile.exists()) finalFile.delete()
                if (tempFile.exists()) tempFile.delete()
                if (legacyTemp.exists()) legacyTemp.delete()
                finalFile.parentFile?.mkdirs()
                if (!finalFile.createNewFile()) throw IOException("创建空文件失败: ${finalFile.absolutePath}")
            }
            if (!verifyFileChecksum(task, finalFile, allowChecksumRetry = false)) {
                throw ChecksumMismatchException("空文件校验失败: ${task.id}")
            }
            markCompletedDirectly(task, finalName, 0L)
            return
        }

        val rangeUnsupported = ctl.acceptRanges?.trim()?.equals("none", ignoreCase = true) == true
        val singleStream = ctl.total < 0 || rangeUnsupported
        if (singleStream) {
            DownloadLog.d(TAG, "使用单流下载: ${task.id}, total=${ctl.total}, acceptRanges=${ctl.acceptRanges}")
        }

        // 保留底层 IOException（如 ENOSPC），否则存储不足会被误判为普通失败
        FileUtils.checkAndCreateFileSafeOrThrow(tempFile)
        checkActive(ctl)

        val tempLength = withContext(Dispatchers.IO) { tempFile.length() }
        val resetTemp = ctl.total > 0 && tempLength > ctl.total
        if (resetTemp) {
            withContext(Dispatchers.IO) { tempFile.delete() }
        }

        val chunkCount = if (singleStream) {
            1
        } else {
            val cfg = DownloadManager.currentConfig()
            ChunkPlanner.calculateChunkCount(ctl.total, cfg.minChunkSizeBytes, cfg.maxChunksPerTask)
        }
        ctl.chunks = prepareChunks(task, ctl, chunkCount, singleStream, resetTemp)

        markDownloading(task, ctl, finalName)
        val running = DownloadManager.getTaskImmediate(task.id)
        if (running == null ||
            running.status == DownloadStatus.PAUSED ||
            running.status == DownloadStatus.CANCELLED ||
            running.status == DownloadStatus.FAILED
        ) {
            throw CancellationException("任务已停止")
        }

        var checksumRetryUsed = false
        while (true) {
            try {
                runChunks(task, ctl)
            } catch (e: RemoteSizeChangedException) {
                // CDN 多副本会在不同请求间返回不同大小，继续分片会混用副本内容。
                // 退回单流全量下载：一次请求只读一个副本，以响应总长度为准。
                checkActive(ctl)
                DownloadLog.w(
                    TAG,
                    "远端大小与探测不一致，切换为单流下载: ${task.id}, ${ctl.total} -> ${e.actualTotal}"
                )
                ctl.total = e.actualTotal
                if (!e.actualETag.isNullOrBlank()) ctl.eTag = e.actualETag
                DownloadManager.updateTaskMeta(task.id, ctl.eTag, ctl.lastModified, ctl.total)
                ProgressCalculatorManager.getCalculator(task.id).clearTaskProgress(task.id)
                resetToSingleStream(task, ctl, openEnded = true)
                markDownloading(task, ctl, finalName)
                try {
                    runChunks(task, ctl)
                } catch (_: FullDownloadFallbackException) {
                    checkActive(ctl)
                    DownloadLog.w(TAG, "服务端忽略 Range，切换为单流全量下载: ${task.id}")
                    resetToSingleStream(task, ctl)
                    runChunks(task, ctl)
                }
            } catch (_: FullDownloadFallbackException) {
                checkActive(ctl)
                DownloadLog.w(TAG, "服务端忽略 Range，切换为单流全量下载: ${task.id}")
                resetToSingleStream(task, ctl)
                runChunks(task, ctl)
            }

            checkActive(ctl)
            if (finishTask(task, ctl, allowChecksumRetry = !checksumRetryUsed)) return
            checksumRetryUsed = true
            resetForChecksumRetry(task, ctl)
        }
    }

    /**
     * 校验最终文件。返回 false 表示调用方应清理断点后重新下载。
     * Retry 策略只自动重试一次，第二次仍不匹配按失败处理。
     */
    private suspend fun verifyFileChecksum(
        task: DownloadTask,
        file: File,
        allowChecksumRetry: Boolean
    ): Boolean {
        val checksum = DownloadManager.getTaskChecksum(task.id) ?: return true
        val actual = withContext(Dispatchers.IO) {
            runCatching { ChecksumVerifier.compute(file, checksum.type) }
                .onFailure { DownloadLog.w(TAG, "计算文件校验值失败: ${file.absolutePath}", it) }
                .getOrNull()
        }
        if (actual != null && actual == ChecksumVerifier.normalizeExpected(checksum.value)) {
            DownloadLog.d(TAG, "文件校验通过: ${task.id}, ${checksum.type}")
            return true
        }

        DownloadLog.w(
            TAG,
            "文件校验失败: ${task.id}, type=${checksum.type}, " +
                    "expected=${checksum.value}, actual=$actual, onFail=${checksum.onFail}"
        )
        when (checksum.onFail) {
            Checksum.OnFail.Ignore -> return true

            Checksum.OnFail.Fail -> {
                withContext(Dispatchers.IO) { file.delete() }
                throw ChecksumMismatchException(
                    "文件校验失败: expected=${checksum.value}, actual=$actual"
                )
            }

            Checksum.OnFail.Retry -> {
                withContext(Dispatchers.IO) { file.delete() }
                if (allowChecksumRetry) return false
                throw ChecksumMismatchException(
                    "文件校验失败（重试后仍不匹配）: expected=${checksum.value}, actual=$actual"
                )
            }
        }
    }

    private suspend fun resetForChecksumRetry(task: DownloadTask, ctl: DownloadController) {
        val chunkManager = ctl.chunkManager ?: throw IllegalStateException("ChunkManager 未初始化")
        DownloadLog.w(TAG, "校验失败，重置断点后重试: ${task.id}")
        if (DownloadManager.getTaskImmediate(task.id) == null) {
            throw CancellationException("任务已删除")
        }
        ProgressCalculatorManager.getCalculator(task.id).clearTaskProgress(task.id)
        withContext(Dispatchers.IO) {
            ctl.tempFile?.let { temp ->
                if (temp.exists()) temp.delete()
                FileUtils.checkAndCreateFileSafe(temp)
            }
        }
        chunkManager.deleteChunks(task.id)
        val singleStream = ctl.total < 0 ||
                ctl.acceptRanges?.trim()?.equals("none", ignoreCase = true) == true
        val chunkCount = if (singleStream) {
            1
        } else {
            val cfg = DownloadManager.currentConfig()
            ChunkPlanner.calculateChunkCount(ctl.total, cfg.minChunkSizeBytes, cfg.maxChunksPerTask)
        }
        ctl.chunks = chunkManager.createChunks(task.id, ctl.total, chunkCount)
        ctl.chunkProgress.clear()
        ctl.chunks.forEach { ctl.chunkProgress[it.index] = 0L }
        markDownloading(task, ctl, ctl.finalFile?.name ?: task.fileName)
        checkActive(ctl)
    }

    private suspend fun prepareChunks(
        task: DownloadTask,
        ctl: DownloadController,
        chunkCount: Int,
        singleStream: Boolean,
        resetTemp: Boolean
    ): List<DownloadChunk> {
        val chunkManager = ctl.chunkManager ?: throw IllegalStateException("ChunkManager 未初始化")
        val existing = chunkManager.getChunks(task.id)
        val layoutValid = !resetTemp && isValidLayout(existing, ctl.total, chunkCount, singleStream)

        val chunks = if (layoutValid) {
            DownloadLog.d(TAG, "恢复分片: ${task.id}, count=${existing.size}")
            existing
        } else {
            if (existing.isNotEmpty()) {
                DownloadLog.w(TAG, "分片布局与服务端不一致，重建分片: ${task.id}")
            }
            chunkManager.deleteChunks(task.id)
            withContext(Dispatchers.IO) {
                ctl.tempFile?.let {
                    it.delete()
                    FileUtils.checkAndCreateFileSafe(it)
                }
            }
            chunkManager.createChunks(task.id, ctl.total, chunkCount)
        }

        val tempLength = withContext(Dispatchers.IO) { ctl.tempFile?.length() ?: 0L }
        val repaired = chunks.map { chunk ->
            if (chunk.downloaded > 0 && chunk.startByte + chunk.downloaded > tempLength) {
                DownloadLog.w(TAG, "分片数据超出临时文件，重置分片: ${task.id} - ${chunk.index}")
                chunkManager.updateChunkProgress(task.id, chunk.index, 0, ChunkStatus.PENDING)
                chunk.copy(downloaded = 0, status = ChunkStatus.PENDING)
            } else {
                chunk
            }
        }

        ctl.chunkProgress.clear()
        repaired.forEach { ctl.chunkProgress[it.index] = it.downloaded }
        return repaired
    }

    private fun isValidLayout(
        chunks: List<DownloadChunk>,
        totalSize: Long,
        chunkCount: Int,
        singleStream: Boolean
    ): Boolean {
        if (chunks.isEmpty() || chunks.size != chunkCount) return false
        if (singleStream) {
            val expectedEnd = if (totalSize > 0) totalSize - 1 else Long.MAX_VALUE
            val chunk = chunks[0]
            return chunk.startByte == 0L &&
                    (chunk.endByte == expectedEnd || chunk.endByte == Long.MAX_VALUE) &&
                    chunk.downloaded >= 0
        }
        if (totalSize <= 0) return false
        if (chunks.first().startByte != 0L || chunks.last().endByte != totalSize - 1) return false
        chunks.forEachIndexed { index, chunk ->
            if (chunk.index != index) return false
            if (chunk.startByte < 0 || chunk.endByte >= totalSize || chunk.endByte < chunk.startByte) return false
            if (chunk.downloaded < 0 || chunk.downloaded > chunk.endByte - chunk.startByte + 1) return false
            if (index > 0 && chunk.startByte != chunks[index - 1].endByte + 1) return false
        }
        return true
    }

    private suspend fun resetToSingleStream(
        task: DownloadTask,
        ctl: DownloadController,
        openEnded: Boolean = false
    ) {
        val chunkManager = ctl.chunkManager ?: throw IllegalStateException("ChunkManager 未初始化")
        chunkManager.deleteChunks(task.id)
        withContext(Dispatchers.IO) {
            ctl.tempFile?.let {
                it.delete()
                FileUtils.checkAndCreateFileSafe(it)
            }
        }
        ctl.chunkProgress.clear()
        // 开放区间让响应中的 Content-Range 总长度成为唯一依据，适配多副本 CDN
        val chunks = chunkManager.createChunks(task.id, if (openEnded) -1L else ctl.total, 1)
        if (chunks.isEmpty()) throw IOException("创建单流分片失败")
        ctl.chunks = chunks
        chunks.forEach { ctl.chunkProgress[it.index] = it.downloaded }
    }

    private suspend fun runChunks(task: DownloadTask, ctl: DownloadController) {
        coroutineScope {
            ctl.chunks.forEach { chunk ->
                launch {
                    downloadChunk(task, chunk, ctl)
                }
            }
        }
    }

    private suspend fun downloadChunk(task: DownloadTask, chunk: DownloadChunk, ctl: DownloadController) {
        if (chunk.status == ChunkStatus.COMPLETED) {
            ctl.chunkProgress[chunk.index] = chunk.downloaded
            return
        }
        checkActive(ctl)

        var retryCount = 0
        var continuationCount = 0
        var lastProgress = chunk.downloaded
        while (true) {
            try {
                if (downloadChunkOnce(task, chunk, ctl)) return
                // 服务端只返回了部分区间，从新进度继续请求
                val progress = ctl.chunkProgress[chunk.index] ?: lastProgress
                if (progress > lastProgress) {
                    lastProgress = progress
                    // 有实际进展的续传不算失败重试
                    retryCount = 0
                } else if (++retryCount > MAX_RETRIES) {
                    throw IOException("分片 ${chunk.index} 多次请求仍未完成")
                }
                if (++continuationCount > MAX_CONTINUATIONS) {
                    throw IOException("分片 ${chunk.index} 续传次数过多")
                }
                DownloadLog.w(TAG, "分片 ${chunk.index} 仅返回部分区间，继续请求: attempt=$continuationCount")
            } catch (e: CancellationException) {
                throw e
            } catch (e: FullDownloadFallbackException) {
                throw e
            } catch (e: RemoteSizeChangedException) {
                throw e
            } catch (e: FileIntegrityException) {
                throw e
            } catch (e: HttpStatusException) {
                if (!e.retryable || ++retryCount > MAX_RETRIES) throw e
                DownloadLog.w(TAG, "分片 ${chunk.index} HTTP ${e.code}，第 $retryCount 次重试")
            } catch (e: IOException) {
                if (!ErrorClassifier.isNetworkError(e) || ++retryCount > MAX_RETRIES) throw e
                DownloadLog.w(TAG, "分片 ${chunk.index} 网络异常，第 $retryCount 次重试: ${e.message}")
            }
            val delayMs = RETRY_DELAYS_MS[(retryCount - 1).coerceIn(0, RETRY_DELAYS_MS.lastIndex)]
            delay(delayMs + Random.nextLong(0, 250))
            checkActive(ctl)
        }
    }

    /**
     * 执行一次分片请求。
     * @return true 表示整个分片已完成；false 表示服务端只返回了合法的一部分，需要继续请求剩余区间
     */
    private suspend fun downloadChunkOnce(
        task: DownloadTask,
        chunk: DownloadChunk,
        ctl: DownloadController
    ): Boolean {
        val chunkManager = ctl.chunkManager ?: throw IllegalStateException("ChunkManager 未初始化")
        // 以控制器中最新进度为准：上一次尝试可能已经写入了部分数据
        val persisted = ctl.chunkProgress[chunk.index] ?: chunk.downloaded
        val startByte = chunk.startByte + persisted
        // 上一次已读到分片末尾但还没来得及落库 COMPLETED（暂停/进程退出）
        if (chunk.endByte != Long.MAX_VALUE && startByte > chunk.endByte) {
            val done = chunk.endByte - chunk.startByte + 1
            ctl.chunkProgress[chunk.index] = done
            chunkManager.updateChunkProgress(task.id, chunk.index, done, ChunkStatus.COMPLETED)
            return true
        }
        val rangeEnd = if (chunk.endByte == Long.MAX_VALUE) "" else chunk.endByte.toString()
        val requestBuilder = Request.Builder()
            .url(task.url)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=$startByte-$rangeEnd")

        DownloadManager.getTaskHeaders(task.id).forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true) &&
                !key.equals("If-Range", ignoreCase = true) &&
                !key.equals("Accept-Encoding", ignoreCase = true)
            ) {
                requestBuilder.header(key, value)
            }
        }

        ctl.eTag?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("If-Range", it) }
            ?: ctl.lastModified?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("If-Range", it) }

        val call = OkHttpHelper.newCall(requestBuilder.get().build())
        ctl.activeCalls[chunk.index] = call
        // 子协程随父协程取消，在 finally 中取消阻塞中的 OkHttp Call
        val cancelWatcher = currentCoroutineContext()[Job]?.let { parent ->
            CoroutineScope(parent + Dispatchers.IO).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
        }

        var completed = false
        try {
            call.execute().use { response ->
                checkActive(ctl)
                val code = response.code
                val body = response.body ?: throw IOException("响应体为空")
                val isFullRangeRequest = startByte == 0L &&
                        (chunk.endByte == Long.MAX_VALUE || (ctl.total > 0 && chunk.endByte >= ctl.total - 1))

                DownloadLog.d(
                    TAG,
                    "分片响应: ${task.id} - ${chunk.index}, code=$code, range=bytes=$startByte-$rangeEnd, length=${body.contentLength()}"
                )

                // 本次响应允许写到的远端终点；未知长度时可能由 Content-Range 补全
                var requestedEnd: Long = if (chunk.endByte == Long.MAX_VALUE && ctl.total > 0) {
                    ctl.total - 1
                } else {
                    chunk.endByte
                }
                var attemptEnd = requestedEnd
                var baseDownloaded: Long
                var expectedAttemptLength: Long

                when {
                    code == 416 -> {
                        // 请求起点已到 EOF：对未知长度/开放区间分片意味着数据已全部取回
                        val total = parseUnsatisfiedRangeTotal(response.header("Content-Range"))
                        if (total != null && startByte == total &&
                            (chunk.endByte == Long.MAX_VALUE || chunk.endByte == total - 1)
                        ) {
                            ctl.total = total
                            val done = startByte - chunk.startByte
                            ctl.chunkProgress[chunk.index] = done
                            chunkManager.updateChunkProgress(
                                task.id, chunk.index, done, ChunkStatus.COMPLETED
                            )
                            completed = true
                            DownloadLog.d(TAG, "分片已到达 EOF: ${task.id} - ${chunk.index}, size=$total")
                            return true
                        }
                        throw FullDownloadFallbackException()
                    }
                    code == 200 && !isFullRangeRequest -> throw FullDownloadFallbackException()
                    code == 206 -> {
                        val contentRange = parseContentRange(response.header("Content-Range"))
                            ?: throw FileIntegrityException("206 响应缺少合法的 Content-Range")
                        if (contentRange.start != startByte) {
                            throw FileIntegrityException(
                                "Content-Range 起点不匹配: expected=$startByte, actual=${contentRange.start}"
                            )
                        }
                        if (contentRange.total > 0) {
                            if (ctl.total > 0 && ctl.total != contentRange.total) {
                                // 单流全量响应（从 0 开始的开放区间）只来自一个副本，
                                // 以响应中的总长度为准即可，不能按分片不一致处理。
                                if (chunk.endByte == Long.MAX_VALUE && startByte == 0L) {
                                    DownloadLog.w(
                                        TAG,
                                        "单流响应大小与探测不一致，采用响应大小: ${task.id}, " +
                                                "${ctl.total} -> ${contentRange.total}"
                                    )
                                    response.header("ETag")?.let { ctl.eTag = it }
                                } else {
                                    throw RemoteSizeChangedException(
                                        contentRange.total,
                                        response.header("ETag")
                                    )
                                }
                            }
                            ctl.total = contentRange.total
                            if (chunk.endByte == Long.MAX_VALUE) {
                                requestedEnd = contentRange.total - 1
                            }
                        }
                        if (contentRange.end > requestedEnd && requestedEnd != Long.MAX_VALUE) {
                            throw FileIntegrityException(
                                "Content-Range 终点超出请求范围: rangeEnd=$requestedEnd, actual=${contentRange.end}"
                            )
                        }
                        attemptEnd = contentRange.end
                        if (attemptEnd < startByte) {
                            throw FileIntegrityException("Content-Range 终点小于起点")
                        }
                        baseDownloaded = persisted
                        expectedAttemptLength = attemptEnd - startByte + 1
                    }
                    code == 200 -> {
                        // 服务端忽略 Range，返回完整内容：仅允许从 0 开始的全量写入
                        if (startByte != 0L) throw FullDownloadFallbackException()
                        val length = body.contentLength()
                        if (ctl.total > 0 && length > 0 && length != ctl.total) {
                            throw FileIntegrityException("响应体长度与文件大小不符: $length/${ctl.total}")
                        }
                        baseDownloaded = 0L
                        if (requestedEnd == Long.MAX_VALUE && ctl.total > 0) {
                            requestedEnd = ctl.total - 1
                            attemptEnd = requestedEnd
                        }
                        // 无 Content-Length 的 200 必须读到 EOF，否则可能把超长响应截断
                        val unknownBodyLength = body.contentLength() <= 0
                        expectedAttemptLength = if (attemptEnd == Long.MAX_VALUE ||
                            (ctl.total > 0 && unknownBodyLength)
                        ) {
                            Long.MAX_VALUE
                        } else {
                            attemptEnd - chunk.startByte + 1
                        }
                        ctl.chunkProgress[chunk.index] = 0L
                    }
                    else -> throw HttpStatusException(code)
                }

                val tempFile = ctl.tempFile ?: throw IllegalStateException("临时文件未初始化")
                if (!tempFile.exists()) throw IOException("临时文件被删除: ${tempFile.absolutePath}")

                val buffer = ByteArray(BUFFER_SIZE)
                var written = 0L
                var lastPersistAt = System.currentTimeMillis()
                var lastPersistedBytes = baseDownloaded
                var lastProgressAt = 0L

                RandomAccessFile(tempFile, "rw").use { raf ->
                    if (ctl.total > 0 && ctl.total >= PREALLOCATE_THRESHOLD) {
                        raf.setLength(ctl.total)
                    }
                    raf.seek(startByte)

                    val source = body.source()
                    while (true) {
                        checkActive(ctl)
                        val readSize = if (expectedAttemptLength == Long.MAX_VALUE) {
                            BUFFER_SIZE
                        } else {
                            val remaining = expectedAttemptLength - written
                            if (remaining <= 0) break
                            minOf(BUFFER_SIZE.toLong(), remaining).toInt()
                        }
                        val read = source.read(buffer, 0, readSize)
                        if (read == -1) {
                            if (expectedAttemptLength != Long.MAX_VALUE && written < expectedAttemptLength) {
                                throw IOException(
                                    "unexpected end of stream: chunk=${chunk.index}, " +
                                            "got=$written, expected=$expectedAttemptLength"
                                )
                            }
                            break
                        }
                        raf.write(buffer, 0, read)
                        written += read
                        val localTotal = baseDownloaded + written
                        ctl.chunkProgress[chunk.index] = localTotal

                        val now = System.currentTimeMillis()
                        if (now - lastPersistAt >= PERSIST_INTERVAL_MS ||
                            localTotal - lastPersistedBytes >= PERSIST_BYTES
                        ) {
                            chunkManager.updateChunkProgress(
                                task.id, chunk.index, localTotal, ChunkStatus.DOWNLOADING
                            )
                            lastPersistAt = now
                            lastPersistedBytes = localTotal
                        }
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                            lastProgressAt = now
                            emitProgress(task, ctl)
                        }
                    }
                }

                if (expectedAttemptLength != Long.MAX_VALUE && written != expectedAttemptLength) {
                    throw FileIntegrityException(
                        "分片 ${chunk.index} 数据长度不匹配: $written/$expectedAttemptLength"
                    )
                }

                val totalDownloaded = baseDownloaded + written
                ctl.chunkProgress[chunk.index] = totalDownloaded

                // 200 全量响应读到 EOF 后才能确认总长度
                if (expectedAttemptLength == Long.MAX_VALUE && ctl.total <= 0) {
                    ctl.total = totalDownloaded
                }

                // 开放区间 + 总长度未知：206 只证明服务端返回了这一段，不能当作 EOF
                if (chunk.endByte == Long.MAX_VALUE && ctl.total <= 0) {
                    chunkManager.updateChunkProgress(
                        task.id, chunk.index, totalDownloaded, ChunkStatus.PENDING
                    )
                    return false
                }

                val knownChunkEnd = when {
                    chunk.endByte != Long.MAX_VALUE -> chunk.endByte
                    ctl.total > 0 -> ctl.total - 1
                    attemptEnd != Long.MAX_VALUE -> attemptEnd
                    else -> Long.MAX_VALUE
                }
                val chunkLength = if (knownChunkEnd == Long.MAX_VALUE) {
                    Long.MAX_VALUE
                } else {
                    knownChunkEnd - chunk.startByte + 1
                }
                if (chunkLength != Long.MAX_VALUE && totalDownloaded < chunkLength) {
                    // 服务端返回了合法但更短的区间，保留进度继续请求
                    chunkManager.updateChunkProgress(
                        task.id, chunk.index, totalDownloaded, ChunkStatus.PENDING
                    )
                    return false
                }
                if (chunkLength != Long.MAX_VALUE && totalDownloaded > chunkLength) {
                    throw FileIntegrityException(
                        "分片 ${chunk.index} 数据超出预期: $totalDownloaded/$chunkLength"
                    )
                }

                chunkManager.updateChunkProgress(task.id, chunk.index, totalDownloaded, ChunkStatus.COMPLETED)
                completed = true
                DownloadLog.d(TAG, "分片下载完成: ${task.id} - ${chunk.index}, 已下载=$totalDownloaded")
                return true
            }
        } finally {
            cancelWatcher?.cancel()
            ctl.activeCalls.remove(chunk.index, call)
            if (!completed) {
                val snapshot = ctl.chunkProgress[chunk.index]
                if (snapshot != null && controllers[task.id] === ctl) {
                    withContext(NonCancellable) {
                        runCatching {
                            chunkManager.updateChunkProgress(
                                task.id, chunk.index, snapshot, ChunkStatus.PENDING
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun emitProgress(task: DownloadTask, ctl: DownloadController) {
        val total = if (ctl.total > 0) ctl.total else 0L
        val snapshot = ctl.chunks.map { chunk ->
            chunk.copy(downloaded = ctl.chunkProgress[chunk.index] ?: chunk.downloaded)
        }
        val calculator = ProgressCalculatorManager.getCalculator(task.id)
        val current = DownloadManager.getTaskImmediate(task.id) ?: return
        if (current.status == DownloadStatus.PAUSED ||
            current.status == DownloadStatus.CANCELLED ||
            current.status == DownloadStatus.COMPLETED
        ) {
            ctl.paused.set(true)
            throw CancellationException("任务已停止")
        }
        val (shouldUpdate, updated) = calculator.calculateProgress(
            task = current,
            chunks = snapshot,
            totalSize = total,
            minUpdateInterval = 500L
        )
        if (shouldUpdate) {
            checkActive(ctl)
            val finalTask = updated.copy(fileName = ctl.finalFile?.name ?: current.fileName)
            if (DownloadManager.updateTaskInternal(finalTask)) {
                DownloadManager.emitProgress(finalTask, finalTask.progress, finalTask.speed)
            }
        }
    }

    /**
     * @return true 表示任务已完成；false 表示校验失败且需要重试（文件已删除、断点待重建）
     */
    private suspend fun finishTask(
        task: DownloadTask,
        ctl: DownloadController,
        allowChecksumRetry: Boolean
    ): Boolean {
        val tempFile = ctl.tempFile ?: throw IOException("临时文件未初始化")
        val finalFile = ctl.finalFile ?: throw IOException("最终文件未初始化")
        val chunks = ctl.chunkManager?.getChunks(task.id) ?: emptyList()
        if (chunks.isEmpty()) throw FileIntegrityException("缺少分片记录")

        val unfinished = chunks.filter { it.status != ChunkStatus.COMPLETED }
        if (unfinished.isNotEmpty()) {
            throw FileIntegrityException("存在未完成的分片: ${unfinished.joinToString { it.index.toString() }}")
        }
        chunks.forEach { chunk ->
            val expected = if (chunk.endByte == Long.MAX_VALUE) {
                chunk.downloaded
            } else {
                chunk.endByte - chunk.startByte + 1
            }
            if (chunk.downloaded != expected) {
                throw FileIntegrityException(
                    "分片 ${chunk.index} 长度不匹配: ${chunk.downloaded}/$expected"
                )
            }
        }

        val downloaded = chunks.sumOf { it.downloaded }
        val tempLength = withContext(Dispatchers.IO) { tempFile.length() }
        if (ctl.total >= 0 && tempLength != ctl.total) {
            throw FileIntegrityException("临时文件大小不匹配: $tempLength/${ctl.total}")
        }
        if (ctl.total < 0) {
            ctl.total = tempLength
        }
        if (downloaded != ctl.total) {
            throw FileIntegrityException("下载字节数不匹配: $downloaded/${ctl.total}")
        }

        val renamed = withContext(Dispatchers.IO) { FileUtils.rename(tempFile, finalFile) }
        if (!renamed) throw IOException("文件重命名失败: ${finalFile.absolutePath}")

        val finalLength = withContext(Dispatchers.IO) { finalFile.length() }
        if (finalLength != ctl.total) {
            throw FileIntegrityException("最终文件大小不匹配: $finalLength/${ctl.total}")
        }

        val current = DownloadManager.getTaskImmediate(task.id)
        if (current == null || current.status == DownloadStatus.CANCELLED) {
            withContext(Dispatchers.IO) { finalFile.delete() }
            return true
        }

        if (!verifyFileChecksum(task, finalFile, allowChecksumRetry)) {
            return false
        }

        val completed = ProgressCalculatorManager.getCalculator(task.id)
            .getFinalProgress(current, chunks, ctl.total)
            .copy(fileName = finalFile.name, currentSize = ctl.total, totalSize = ctl.total)

        if (!DownloadManager.updateTaskInternal(completed)) {
            // 任务被删除/取消时才丢弃文件；若是暂停等非终态错过了完成写入，
            // 保留已完整落盘的文件，恢复时会按大小直接判定完成。
            val latest = DownloadManager.getTaskImmediate(task.id)
            if (latest == null || latest.status == DownloadStatus.CANCELLED) {
                withContext(Dispatchers.IO) { finalFile.delete() }
            }
            ProgressCalculatorManager.clearCalculator(task.id)
            return true
        }
        DownloadManager.emitProgress(completed, completed.progress, 0L)
        ProgressCalculatorManager.clearCalculator(task.id)
        DownloadLog.d(TAG, "任务下载完成: ${task.id}, size=${completed.totalSize}")
        return true
    }

    private fun markDownloading(task: DownloadTask, ctl: DownloadController, finalName: String) {
        val current = DownloadManager.getTaskImmediate(task.id) ?: return
        if (current.status == DownloadStatus.PAUSED ||
            current.status == DownloadStatus.CANCELLED ||
            current.status == DownloadStatus.COMPLETED ||
            current.status == DownloadStatus.FAILED
        ) {
            return
        }
        val downloaded = ctl.chunkProgress.values.sum()
        val updated = current.copy(
            fileName = finalName,
            status = DownloadStatus.DOWNLOADING,
            totalSize = if (ctl.total > 0) ctl.total else current.totalSize,
            currentSize = downloaded,
            progress = calculateProgress(downloaded, ctl.total),
            speed = 0L,
            updateTime = TimeUtils.currentMicros()
        )
        if (!DownloadManager.updateTaskInternal(updated)) {
            throw CancellationException("任务已停止")
        }
        DownloadManager.emitProgress(updated, updated.progress, 0L)
    }

    private fun markCompletedDirectly(task: DownloadTask, finalName: String, totalSize: Long) {
        val current = DownloadManager.getTaskImmediate(task.id) ?: task
        if (current.status == DownloadStatus.CANCELLED) return
        val completed = current.copy(
            fileName = finalName,
            status = DownloadStatus.COMPLETED,
            progress = 100,
            totalSize = totalSize,
            currentSize = totalSize,
            speed = 0L,
            updateTime = TimeUtils.currentMicros()
        )
        if (!DownloadManager.updateTaskInternal(completed)) {
            ProgressCalculatorManager.clearCalculator(task.id)
            return
        }
        DownloadManager.emitProgress(completed, 100, 0L)
        ProgressCalculatorManager.clearCalculator(task.id)
    }

    private suspend fun handleFailure(task: DownloadTask, ctl: DownloadController, error: Throwable) {
        DownloadLog.e(TAG, "下载异常: ${task.id}", error)
        val current = DownloadManager.getTaskImmediate(task.id)
        if (current == null ||
            current.status == DownloadStatus.PAUSED ||
            current.status == DownloadStatus.CANCELLED ||
            current.status == DownloadStatus.COMPLETED
        ) {
            return
        }

        val pauseReason = ErrorClassifier.getPauseReason(error)
        if (pauseReason != null) {
            val downloaded = if (ctl.chunkProgress.isEmpty()) {
                current.currentSize
            } else {
                ctl.chunkProgress.values.sum()
            }
            val total = if (ctl.total > 0) ctl.total else current.totalSize
            val paused = current.copy(
                status = DownloadStatus.PAUSED,
                pauseReason = pauseReason,
                progress = calculateProgress(downloaded, total),
                currentSize = downloaded,
                totalSize = total,
                speed = 0L,
                updateTime = TimeUtils.currentMicros()
            )
            if (DownloadManager.updateTaskInternal(paused)) {
                DownloadManager.emitProgress(paused, paused.progress, 0L)
            }
            if (pauseReason == com.pichs.download.model.PauseReason.STORAGE_FULL) {
                releasePreallocatedSpace(ctl)
            }
            DownloadLog.d(TAG, "任务因 $pauseReason 暂停: ${task.id}")
        } else {
            val failed = current.copy(
                status = DownloadStatus.FAILED,
                speed = 0L,
                updateTime = TimeUtils.currentMicros()
            )
            DownloadManager.updateTaskInternal(failed)
            DownloadManager.emitProgress(failed, failed.progress, 0L)
            DownloadLog.d(TAG, "任务失败: ${task.id}")
        }
    }

    /**
     * 存储不足时把预分配的临时文件截断到真实写入位置，先把预留空间还给系统。
     * 分片进度本身就是相对各分片起点的字节数，取最大绝对位置可保留所有已写入数据。
     */
    private suspend fun releasePreallocatedSpace(ctl: DownloadController) {
        val tempFile = ctl.tempFile ?: return
        if (ctl.chunks.isEmpty()) return
        val maxWritten = ctl.chunks.maxOfOrNull { chunk ->
            chunk.startByte + (ctl.chunkProgress[chunk.index] ?: 0L)
        } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                if (tempFile.exists() && tempFile.length() > maxWritten) {
                    RandomAccessFile(tempFile, "rw").use { it.setLength(maxWritten) }
                    DownloadLog.w(
                        TAG,
                        "存储不足，临时文件截断到真实进度: ${tempFile.name}, $maxWritten"
                    )
                }
            }.onFailure {
                DownloadLog.w(TAG, "临时文件截断失败: ${tempFile.absolutePath}", it)
            }
        }
    }

    private suspend fun checkActive(ctl: DownloadController) {
        if (ctl.paused.get() || ctl.cancelled.get()) {
            throw CancellationException("任务已停止")
        }
        currentCoroutineContext().ensureActive()
    }

    private fun calculateProgress(downloaded: Long, totalSize: Long): Int {
        if (totalSize <= 0) return 0
        return ((downloaded * 100) / totalSize).toInt().coerceIn(0, 100)
    }

    /**
     * 避免不同任务写入同一个最终文件。
     * 任务自身的断点续传（已有临时文件）保持原文件名，避免每次恢复都改名。
     */
    private fun resolveFinalFileName(
        dir: File,
        baseName: String,
        taskId: String,
        hasOwnProgress: Boolean
    ): String {
        val canonicalDir = FileUtils.canonicalPath(dir.absolutePath)
        val otherNames = InMemoryTaskStore.getAll()
            .asSequence()
            .filter {
                it.id != taskId &&
                        FileUtils.canonicalPath(it.filePath) == canonicalDir
            }
            .filter { it.status != DownloadStatus.CANCELLED && it.status != DownloadStatus.FAILED }
            .map { it.fileName }
            .toSet()
        return finalNameRegistry.resolve(dir, baseName, taskId, otherNames, hasOwnProgress)
    }
}
