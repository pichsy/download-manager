package com.pichs.download.call


import android.util.Log
import com.pichs.download.DownloadTask
import com.pichs.download.breakpoint.DownloadBreakPointData
import com.pichs.download.breakpoint.DownloadBreakPointManger
import com.pichs.download.breakpoint.DownloadChunk
import com.pichs.download.breakpoint.DownloadChunkManager
import com.pichs.download.utils.DownloadLog
import com.pichs.download.utils.FileUtils
import com.pichs.download.utils.MD5Utils
import com.pichs.download.utils.OkHttpHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

class DownloadMultiCall : CoroutineScope by (CoroutineScope(SupervisorJob() + Dispatchers.Main)) {

    companion object {
        // 8MB，缓冲区
        private const val BUFFER_SIZE = 16  * 1024L

        // 1 connection: [0, 1MB)
        const val ONE_CONNECTION_UPPER_LIMIT: Long = (1024 * 1024L)

        // 2 connection: [1MB, 5MB)
        const val TWO_CONNECTION_UPPER_LIMIT: Long = (5 * 1024 * 1024L)

        // 3 connection: [5MB, 50MB)
        const val THREE_CONNECTION_UPPER_LIMIT: Long = (50 * 1024 * 1024L)

        // 4 connection: [50MB, 100MB)
        const val FOUR_CONNECTION_UPPER_LIMIT: Long = (100 * 1024 * 1024L)

        private fun getBlockCount(totalLength: Long): Int {
            return if (totalLength < ONE_CONNECTION_UPPER_LIMIT) {
                1
            } else if (totalLength < TWO_CONNECTION_UPPER_LIMIT) {
                1
            } else if (totalLength < THREE_CONNECTION_UPPER_LIMIT) {
               2
            } else if (totalLength < FOUR_CONNECTION_UPPER_LIMIT) {
              3
            } else {
                4
            }
        }
    }


    fun download(task: DownloadTask?, onProgress: (DownloadTask, Long, Long, Int, Long) -> Unit) {
        DownloadLog.d { "DownloadCall download: ${task?.downloadInfo?.url}" }
        launch(Dispatchers.IO) {
            try {
                val url = task?.downloadInfo?.url ?: throw IOException("Url is null")
                val filePath = task.downloadInfo?.filePath ?: throw IOException("File path is null")
                val headerData = OkHttpHelper.getFileTotalLengthFromUrl(url)
                val totalLength = headerData.contentLength
                if (totalLength <= 0L) throw IOException("Content length is zero or negative")
                val contentType = headerData.contentType
                val fileName = FileUtils.generateFilename(task.downloadInfo?.fileName, url, contentType)

                val file = File(filePath, fileName)

                val fileMD5 = MD5Utils.fileCheckMD5(file.absolutePath)

                DownloadLog.d { "download666: 已下载文件MD5 fileMD5:$fileMD5" }

                // 创建临时文件
                val tempFile = File(filePath, "$fileName.tmp")
                val finalFile = File(filePath, fileName)

                // 检查最终文件是否已经存在且有效
                // todo 后期要追加这个下载。
                if (FileUtils.isFileValid(finalFile, totalLength, fileMD5 = null)) {
                    onProgress(task, totalLength, totalLength, 100, 0)
                    updateProgress(task, totalLength, totalLength, 100, 0)
                    return@launch
                }

                FileUtils.checkAndCreateFileSafeWithLength(tempFile, totalLength)

                // Get or create breakpoint info
                val breakpointInfo = getOrCreateBreakpointInfo(task, totalLength, fileName)
                val progressTracker = ProgressTracker(totalLength)
                progressTracker.addProgress(breakpointInfo.currentLength ?: 0L)

                val blockCount = getBlockCount(totalLength)
                DownloadLog.d { "download666: 下载分块数量 chunkCount:$blockCount ,totalLength:$totalLength" }

                val jobs = List(blockCount) { index ->
                    async(Dispatchers.IO) {
                        val chunk = getOrCreateChunk(task.downloadInfo?.taskId ?: "", index, totalLength, blockCount)
                        downloadPart(url, tempFile, chunk, progressTracker) { currentBytes ->
                            if (progressTracker.shouldUpdateProgress()) {
                                val speed = progressTracker.calculateSpeed()
                                val progress = progressTracker.getProgress()

                                onProgress(task, currentBytes, totalLength, progress, speed)
                                updateProgress(task, currentBytes, totalLength, progress, speed)
                            }
                        }
                    }
                }

                jobs.awaitAll()
                val isRenameSuccess = FileUtils.rename(tempFile, finalFile)
                DownloadLog.d { "download666: 文件重命名 isRenameSuccess:$isRenameSuccess" }
                if (!isRenameSuccess) throw IOException("文件重命名失败！")
                // 确保最终进度为100%
                onProgress(task, totalLength, totalLength, 100, 0)
                updateProgress(task, totalLength, totalLength, 100, 0)
                onDownloadComplete(task)
            } catch (e: Exception) {
                onDownloadFailed(task, e)
            }
        }
    }

    private suspend fun downloadPart(
        url: String,
        file: File,
        chunk: DownloadChunk,
        progressTracker: ProgressTracker,
        onPartProgress: (Long) -> Unit
    ) {
        var retries = 3
        while (retries > 0) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=${chunk.start}-${chunk.end}")
                    .build()

                val response = OkHttpHelper.execute(request)
                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                val body = response.body ?: throw IOException("Null response body")

                withContext(Dispatchers.IO) {
                    body.source().use { source ->
                        RandomAccessFile(file, "rwd").use { randomAccessFile ->
                            randomAccessFile.seek(chunk.start)
                            val buffer = ByteArray(BUFFER_SIZE.toInt())
                            var bytesRead: Int
                            while (source.read(buffer).also { bytesRead = it } != -1) {
                                randomAccessFile.write(buffer, 0, bytesRead)
                                val currentBytes = progressTracker.addProgress(bytesRead.toLong())
                                chunk.downloadedBytes += bytesRead
                                DownloadChunkManager.upsert(chunk)
                                onPartProgress(currentBytes)
                            }
                        }
                    }
                }
                return
            } catch (e: SocketTimeoutException) {
                retries--
                if (retries == 0) throw e
                delay(5000)
            } catch (e: IOException) {
                throw e
            }
        }
    }

    private fun getOrCreateBreakpointInfo(task: DownloadTask, totalLength: Long, fileName: String): DownloadBreakPointData {
        val taskId = task.downloadInfo?.taskId ?: ""
        val existingInfo = DownloadBreakPointManger.queryByTaskId(taskId)
        return if (existingInfo != null) {
            existingInfo
        } else {
            val newInfo = DownloadBreakPointData(
                taskId = taskId,
                url = task.downloadInfo?.url ?: "",
                filePath = task.downloadInfo?.filePath ?: "",
                fileName = fileName,
                totalLength = totalLength,
                currentLength = 0,
            )
            DownloadBreakPointManger.upsert(newInfo)
            newInfo
        }
    }

    private fun getOrCreateChunk(taskId: String, index: Int, totalLength: Long, blockCount: Int): DownloadChunk {
        val chunks = DownloadChunkManager.queryChunkByTaskId(taskId)
        return chunks?.find { it.chunkIndex == index } ?: run {
            val chunkSize = totalLength / blockCount
            val startByte = index * chunkSize
            val endByte = if (index == blockCount - 1) totalLength - 1 else (index + 1) * chunkSize - 1
            DownloadChunk(
                taskId = taskId,
                chunkIndex = index,
                start = startByte,
                end = endByte,
                downloadedBytes = 0
            ).also {
                DownloadChunkManager.upsert(it)
            }
        }
    }

    private fun updateProgress(task: DownloadTask, currentLength: Long, totalLength: Long, progress: Int, speed: Long) {
        task.downloadInfo?.apply {
            this.currentLength = currentLength
            this.totalLength = totalLength
            this.progress = progress
            this.status = 1 // 下载中
        }
        DownloadBreakPointManger.queryByTaskId(task.downloadInfo?.taskId ?: "")?.let {
            it.currentLength = currentLength
            it.updateTime = System.currentTimeMillis()
            DownloadBreakPointManger.upsert(it)
        }
    }

    private fun onDownloadComplete(task: DownloadTask) {
        task.downloadInfo?.status = 3 // 完成
        DownloadBreakPointManger.deleteByTaskId(task.downloadInfo?.taskId ?: "")
        DownloadChunkManager.deleteChunkByTaskId(task.downloadInfo?.taskId ?: "")
    }

    private fun onDownloadFailed(task: DownloadTask?, error: Exception) {
        task?.downloadInfo?.status = 4 // 失败
    }
}

class ProgressTracker(private val totalLength: Long) {
    private val downloadedBytes = AtomicLong(0)
    private val lastUpdateTime = AtomicLong(System.currentTimeMillis())
    private val lastBytesRead = AtomicLong(0)

    fun addProgress(bytes: Long): Long {
        return downloadedBytes.addAndGet(bytes).coerceAtMost(totalLength)
    }

    fun getProgress(): Int {
        return ((downloadedBytes.get().toDouble() / totalLength) * 100).toInt().coerceIn(0, 100)
    }

    fun calculateSpeed(): Long {
        val currentTime = System.currentTimeMillis()
        val currentBytes = downloadedBytes.get()
        val timeDiff = (currentTime - lastUpdateTime.get()) / 1000.0
        val bytesDiff = currentBytes - lastBytesRead.get()
        val speed = if (timeDiff > 0) (bytesDiff / timeDiff).toLong() else 0L

        lastUpdateTime.set(currentTime)
        lastBytesRead.set(currentBytes)

        return speed
    }

    fun getTotalDownloaded(): Long {
        return downloadedBytes.get()
    }

    fun shouldUpdateProgress(): Boolean {
        return System.currentTimeMillis() - lastUpdateTime.get() >= PROGRESS_UPDATE_INTERVAL
    }

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL = 1500 // 1 second
    }
}
