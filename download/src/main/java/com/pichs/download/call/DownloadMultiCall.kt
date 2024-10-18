package com.pichs.download.call


import android.util.Log
import com.pichs.download.DownloadTask
import com.pichs.download.utils.FileUtils
import com.pichs.download.utils.OkHttpHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import java.net.SocketTimeoutException

class DownloadMultiCall : CoroutineScope by (CoroutineScope(SupervisorJob() + Dispatchers.Main)) {

    companion object {
        private const val BUFFER_SIZE = 8 * 1024 * 1024 // 8MB buffer
        private const val PROGRESS_UPDATE_INTERVAL = 1000 // 1 second
        private const val THREAD_COUNT = 5
    }

    fun download(task: DownloadTask?, onProgress: (DownloadTask, Long, Long, Int, Long) -> Unit) {
        Log.d("DownloadCall", "download: ${task?.downloadInfo?.url}")
        launch(Dispatchers.IO) {
            try {
                val url = task?.downloadInfo?.url ?: throw IOException("Url is null")
                val filePath = task.downloadInfo?.filePath ?: throw IOException("File path is null")
                val headerData = OkHttpHelper.getFileTotalLengthFromUrl(url)
                val totalLength = headerData.contentLength
                if (totalLength <= 0L) throw IOException("Content length is zero or negative")
                val contentType = headerData.contentType
                // 获取名字，带后缀的。
                val fileName = FileUtils.generateFilename(task.downloadInfo?.fileName, url, contentType)

                val file = File(filePath, fileName)

                FileUtils.checkAndCreateFileSafeWithLength(file, totalLength)

                val partSize = totalLength / THREAD_COUNT
                val remainingBytes = totalLength % THREAD_COUNT

                val downloadedBytes = AtomicLong(0)
                var lastUpdateTime = System.currentTimeMillis()
                var lastBytesRead = 0L

                val jobs = List(THREAD_COUNT) { index ->
                    async(Dispatchers.IO) {
                        val start = index * partSize
                        val end = if (index == THREAD_COUNT - 1) start + partSize + remainingBytes - 1 else start + partSize - 1
                        downloadPart(url, file, start, end, downloadedBytes) { currentBytes ->
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= PROGRESS_UPDATE_INTERVAL) {
                                val timeDiff = (currentTime - lastUpdateTime) / 1000.0
                                val bytesDiff = currentBytes - lastBytesRead
                                val speed = (bytesDiff / timeDiff).toLong()

                                val progress = (currentBytes * 100 / totalLength).toInt()

                                onProgress(task, currentBytes, totalLength, progress, speed)
                                updateProgress(task, currentBytes, totalLength, progress, speed)

                                lastUpdateTime = currentTime
                                lastBytesRead = currentBytes
                            }
                        }
                    }
                }

                jobs.awaitAll()

                onProgress(task, totalLength, totalLength, 100, 0)
                updateProgress(task, totalLength, totalLength, 100, 0)
                onDownloadComplete(task)
            } catch (e: SocketTimeoutException) {
                onDownloadFailed(task, e)
            } catch (e: IOException) {
                onDownloadFailed(task, e)
            }
        }
    }

    private suspend fun downloadPart(
        url: String,
        file: File,
        start: Long,
        end: Long,
        downloadedBytes: AtomicLong,
        onPartProgress: (Long) -> Unit
    ) {
        var retries = 3
        while (retries > 0) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$start-$end")
                    .build()

                val response = OkHttpHelper.execute(request)
                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                val body = response.body ?: throw IOException("Null response body")

                withContext(Dispatchers.IO) {
                    body.source().use { source ->
                        RandomAccessFile(file, "rw").use { randomAccessFile ->
                            randomAccessFile.seek(start)
                            val buffer = ByteArray(BUFFER_SIZE.toInt())
                            var bytesRead: Int
                            while (source.read(buffer).also { bytesRead = it } != -1) {
                                randomAccessFile.write(buffer, 0, bytesRead)
                                val currentBytes = downloadedBytes.addAndGet(bytesRead.toLong())
                                onPartProgress(currentBytes)
                            }
                        }
                    }
                }
                return // 成功完成下载，退出函数
            } catch (e: SocketTimeoutException) {
                retries--
                if (retries == 0) throw e // 重试次数用完，抛出异常
                delay(5000) // 等待5秒后重试
            } catch (e: IOException) {
                throw e // 其他IO异常直接抛出
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
//        task.notifyListeners { it.onProgress(task, currentLength, totalLength, progress, speed) }
    }

    private fun onDownloadComplete(task: DownloadTask) {
        task.downloadInfo?.status = 3 // 完成
//        task.notifyListeners { it.onComplete(task) }
    }

    private fun onDownloadFailed(task: DownloadTask?, error: Exception) {
        task?.downloadInfo?.status = 4 // 失败
//        task?.notifyListeners { it.onError(task, error) }
    }

}
