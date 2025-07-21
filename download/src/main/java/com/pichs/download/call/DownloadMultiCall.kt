package com.pichs.download.call


import com.pichs.download.DownloadTask
import com.pichs.download.breakpoint.DownloadBreakPointData
import com.pichs.download.breakpoint.DownloadBreakPointManger
import com.pichs.download.breakpoint.DownloadChunk
import com.pichs.download.breakpoint.DownloadChunkManager
import com.pichs.download.dispatcher.DispatcherListener
import com.pichs.download.entity.DownloadStatus
import com.pichs.download.utils.DownloadLog
import com.pichs.download.utils.FileUtils
import com.pichs.download.utils.MD5Utils
import com.pichs.download.utils.OkHttpHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong

class DownloadMultiCall(val task: DownloadTask) : CoroutineScope by MainScope() {

    companion object {
        // 8MB，缓冲区
        private const val BUFFER_SIZE = 64 * 1024

        //todo 经过验证3块 可以达到10M/s的速度。比较合理。
        // 再大就不行了。

        // 1 connection: [10M)
        const val ONE_CONNECTION_UPPER_LIMIT: Long = (10 * 1024 * 1024L)

        // 2 connection: [50)
        const val TWO_CONNECTION_UPPER_LIMIT: Long = (70 * 1024 * 1024L)

        // 4 connection: [100MB)
        const val THREE_CONNECTION_UPPER_LIMIT: Long = (90 * 1024 * 1024L)

        // 4 connection: [120MB)
        const val FOUR_CONNECTION_UPPER_LIMIT: Long = (150 * 1024 * 1024L)

        private fun getBlockCount(totalLength: Long): Int {
            return if (totalLength <= ONE_CONNECTION_UPPER_LIMIT) {
                1
            } else if (totalLength <= TWO_CONNECTION_UPPER_LIMIT) {
                2
            } else {
                3
            }
        }
    }

    private var listener: DispatcherListener? = null

    fun setListener(downloadListener: DispatcherListener): DownloadMultiCall {
        this.listener = downloadListener
        return this
    }

    private var job: Job? = null

    fun startCall(): DownloadMultiCall {
        DownloadLog.d { "download666 DownloadCall download: ${task.downloadInfo?.url}" }
        job = launch(Dispatchers.IO) {
            try {
                val url = task.downloadInfo.url
                if (url.isEmpty()) throw IOException("DownloadTask: url is  empty, can't download.")
                val filePath = task.downloadInfo.filePath
                val headerData = OkHttpHelper.getFileTotalLengthFromUrl(url)
                val totalLength = headerData.contentLength
                if (totalLength <= 0L) throw IOException("Content length is zero or negative")
                val contentType = headerData.contentType
                val fileName = FileUtils.generateFilename(task.downloadInfo?.fileName, url, contentType)

                task.downloadInfo.apply {
                    this.fileName = fileName
                    this.filePath = filePath
                }

                val file = File(filePath, fileName)

                val fileMD5 = MD5Utils.fileCheckMD5(file.absolutePath)

                DownloadLog.d { "download666: 已下载文件MD5 fileMD5:$fileMD5" }

                // 创建临时文件
                val tempFile = File(filePath, "$fileName.tmp")
                val finalFile = File(filePath, fileName)

                // 检查最终文件是否已经存在且有效
                // todo 后期要追加这个下载。
                if (FileUtils.isFileValid(finalFile, totalLength, fileMD5 = task.downloadInfo?.fileMD5)) {
                    // 下载完成。直接返回
                    listener?.onComplete(this@DownloadMultiCall, task)
                    // updateBreakPointData(null, totalLength)
                    return@launch
                }

                FileUtils.checkAndCreateFileSafeWithLength(tempFile, totalLength)

                // 获取或者新增：breakpoint Data
                val breakpointInfo = getOrCreateBreakpointData(task, totalLength, fileName)
                val progressTracker = ProgressTracker(totalLength)
                progressTracker.addProgress(breakpointInfo.currentLength ?: 0L)

                val blockCount = getBlockCount(totalLength)
                DownloadLog.d { "download666: 下载分块数量 chunkCount:$blockCount ,totalLength:$totalLength" }
                val chunks = DownloadChunkManager.queryChunkByTaskId(task.downloadInfo?.taskId ?: "")

                // 开始下载
                task.downloadInfo?.totalLength = totalLength
                task.downloadInfo?.status = DownloadStatus.DOWNLOADING // 下载中
                listener?.onStart(this@DownloadMultiCall, task, totalLength)

                val jobs = List(blockCount) { index ->
                    async(Dispatchers.IO) {
                        val chunk = getOrCreateChunk(chunks, task.downloadInfo?.taskId ?: "", index, totalLength, blockCount)
                        downloadPart(url, tempFile, chunk, progressTracker) { chunkUpdate, currentBytes ->
                            if (progressTracker.shouldUpdateProgress()) {
                                val speed = progressTracker.calculateSpeed()
                                val progress = progressTracker.getProgress()
                                task.downloadInfo?.apply {
                                    this.currentLength = currentBytes
                                    this.totalLength = totalLength
                                    this.progress = progress
                                    this.status = 1 // 下载中
                                }
                                DownloadLog.d { "download666: filePath:${file.absolutePath}下载进度 progress:$progress" }
                                listener?.onProgress(this@DownloadMultiCall, task, currentBytes, totalLength, progress, speed)
//                                onProgress(task, currentBytes, totalLength, progress, speed)
                                launch {
                                    // todo 更新数据库缓存。
                                    DownloadChunkManager.upsert(chunkUpdate)
                                    updateBreakPointData(breakpointInfo, currentBytes)
                                }
                            }
                        }
                    }
                }

                jobs.awaitAll()

                val isRenameSuccess = FileUtils.rename(tempFile, finalFile)
                DownloadLog.d { "download666: 文件重命名 isRenameSuccess:$isRenameSuccess" }
                if (!isRenameSuccess) throw IOException("文件重命名失败！")
                // 确保最终进度为100%
                task.downloadInfo?.apply {
                    this.currentLength = totalLength
                    this.totalLength = totalLength
                    this.progress = 100
                    this.status = DownloadStatus.DOWNLOADING // 下载中
                }
                listener?.onProgress(this@DownloadMultiCall, task, totalLength, totalLength, 100, 0)

                breakpointInfo.progress = 100
                breakpointInfo.currentLength = totalLength
                breakpointInfo.status = DownloadStatus.COMPLETED // 完成
                breakpointInfo.updateTime = System.currentTimeMillis()

                updateBreakPointData(breakpointInfo, totalLength)
                onDownloadComplete(task)
            } catch (e: Exception) {
                // 获取或者新增：breakpoint Data
                getBreakpointData(task.getTaskId())?.let {
                    updateBreakPointData(it, it.currentLength)
                }
                onDownloadFailed(task, e)
            }
        }
        return this
    }

    private suspend fun downloadPart(
        url: String,
        file: File,
        chunk: DownloadChunk,
        progressTracker: ProgressTracker,
        onPartProgress: CoroutineScope.(chunk: DownloadChunk, progress: Long) -> Unit
    ) {
        var retries = 3
        var delayRetry = 3000L
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
                        // Cursor 优化后的代码
                        FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.READ).use { channel ->
                            channel.position(chunk.start)
                            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
                            var bytesRead: Int
                            while (source.read(buffer.array(), 0, BUFFER_SIZE).also { bytesRead = it } != -1) {
                                buffer.clear()
                                buffer.limit(bytesRead)
                                channel.write(buffer)
                                val currentBytes = progressTracker.addProgress(bytesRead.toLong())
                                chunk.downloadedBytes += bytesRead
                                onPartProgress.invoke(this, chunk, currentBytes)
                            }
                        }
                        // old 代码。
//                        RandomAccessFile(file, "rwd").use { randomAccessFile ->
//                            randomAccessFile.seek(chunk.start)
//                            val buffer = ByteArray(BUFFER_SIZE.toInt())
//                            var bytesRead: Int
//                            while (source.read(buffer).also { bytesRead = it } != -1) {
//                                randomAccessFile.write(buffer, 0, bytesRead)
//                                val currentBytes = progressTracker.addProgress(bytesRead.toLong())
//                                chunk.downloadedBytes += bytesRead
//                                DownloadChunkManager.upsert(chunk)
//                                onPartProgress(currentBytes)
//                            }
//                        }
                    }
                }
                return
            } catch (e: SocketTimeoutException) {
                retries--
                if (retries == 0) throw e
                delay(delayRetry)
                delayRetry *= 2
            } catch (e: IOException) {
                throw e
            }
        }
    }

    private suspend fun getOrCreateBreakpointData(task: DownloadTask, totalLength: Long, fileName: String): DownloadBreakPointData {
        val taskId = task.downloadInfo.taskId ?: ""
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
                extra = task.downloadInfo.extra
            )
            DownloadBreakPointManger.upsert(newInfo)
            newInfo
        }
    }

    private suspend fun getBreakpointData(taskId: String): DownloadBreakPointData? {
        return DownloadBreakPointManger.queryByTaskId(taskId)
    }

    private suspend fun getOrCreateChunk(
        chunks: MutableList<DownloadChunk>?,
        taskId: String,
        index: Int,
        totalLength: Long,
        blockCount: Int
    ): DownloadChunk {
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

    private suspend fun updateBreakPointData(
        breakPointData: DownloadBreakPointData?,
        currentLength: Long,
    ) {
        breakPointData?.let {
            it.currentLength = currentLength
            it.updateTime = System.currentTimeMillis()
            DownloadBreakPointManger.upsert(it)
        }
    }

    private suspend fun onDownloadComplete(task: DownloadTask) {
        task.downloadInfo?.status = DownloadStatus.COMPLETED // 完成
        listener?.onComplete(this@DownloadMultiCall, task)
//        DownloadBreakPointManger.deleteByTaskId(task.downloadInfo?.taskId ?: "")
//        DownloadChunkManager.deleteChunkByTaskId(task.downloadInfo?.taskId ?: "")
    }

    private suspend fun onDownloadFailed(task: DownloadTask?, error: Exception) {
        task?.downloadInfo?.status = DownloadStatus.ERROR // 失败
        listener?.onError(this@DownloadMultiCall, task, error)
    }

    fun resumeCall(): DownloadMultiCall {
        startCall()
        return this
    }

    /**
     * 取消下载。
     */
    fun pauseCall(): DownloadMultiCall {
        job?.cancel()
        job = null
        task.downloadInfo?.status = DownloadStatus.PAUSE // 暂停
        listener?.onPause(this@DownloadMultiCall, task)
        return this
    }

    /**
     * 取消下载并删除文件。
     */
    fun cancelCall(): DownloadMultiCall {
        job?.cancel()
        job = null
        task.downloadInfo?.status = DownloadStatus.CANCEL // 取消
        launch(Dispatchers.IO) {
            // 清除数据库数据
            DownloadBreakPointManger.deleteByTaskId(task.downloadInfo?.taskId ?: "")
            DownloadChunkManager.deleteChunkByTaskId(task.downloadInfo?.taskId ?: "")
        }
        listener?.onCancel(this@DownloadMultiCall, task)
        return this
    }

    /**
     * 清除下载任务。
     */
    fun clearCall(): DownloadMultiCall {
        job?.cancel()
        job = null
        task.downloadInfo?.status = DownloadStatus.CANCEL // 清除
        launch(Dispatchers.IO) {
            // 清除数据库数据
            DownloadBreakPointManger.deleteByTaskId(task.downloadInfo?.taskId ?: "")
            DownloadChunkManager.deleteChunkByTaskId(task.downloadInfo?.taskId ?: "")
        }
        listener?.onCancel(this@DownloadMultiCall, task)
        return this
    }

}


