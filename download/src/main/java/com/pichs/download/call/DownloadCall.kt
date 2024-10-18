package com.pichs.download.call

import android.util.Log
import com.pichs.download.DownloadTask
import com.pichs.download.utils.FileUtils
import com.pichs.download.utils.OkHttpHelper
import kotlinx.coroutines.*
import okhttp3.Request
import okio.appendingSink
import okio.buffer
import java.io.*

class DownloadCall : CoroutineScope by (CoroutineScope(SupervisorJob() + Dispatchers.Main)) {

    companion object {
        private const val BUFFER_SIZE = 8 * 1024
        private const val PROGRESS_UPDATE_INTERVAL = 1000L // in milliseconds
    }

    fun download(task: DownloadTask?, onProgress: (DownloadTask, Long, Long, Int, Long) -> Unit) {
        launch(Dispatchers.IO) {
            try {
                val url = task?.downloadInfo?.url ?: throw IOException("Url is null")
                val filePath = task.downloadInfo?.filePath ?: throw IOException("File path is null")
                // Get the total file length first
                val headerData = OkHttpHelper.getFileTotalLengthFromUrl(url)
                val totalLength = headerData.contentLength
                if (totalLength <= 0L) throw IOException("Content length is zero or negative")

                val contentType = headerData.contentType
                // 获取名字，带后缀的。
                val fileName = FileUtils.generateFilename(task.downloadInfo?.fileName, url, contentType)

                val file = File(filePath, fileName)

                // 文件处理。
                FileUtils.checkAndCreateFileSafe(file)

                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=${file.length()}-")
                    .build()

                val response = OkHttpHelper.execute(request)
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                val body = response.body ?: throw IOException("Null response body")
                var lastUpdateTime = System.currentTimeMillis()
                var lastBytesRead = file.length()
                body.source().use { source ->
                    file.appendingSink().buffer().use { sink ->
                        var totalBytesRead = file.length()
                        var bytesRead: Long
                        while (source.read(sink.buffer, BUFFER_SIZE.toLong()).also { bytesRead = it } != -1L) {
                            sink.emit()
                            totalBytesRead += bytesRead
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= PROGRESS_UPDATE_INTERVAL) {
                                val timeDiff = (currentTime - lastUpdateTime) / 1000.0 // in seconds
                                val bytesDiff = totalBytesRead - lastBytesRead
                                val speed = (bytesDiff / timeDiff).toLong() // bytes per second
                                val progress = (totalBytesRead * 100 / totalLength).toInt()
                                onProgress(task, totalBytesRead, totalLength, progress, speed)
                                lastUpdateTime = currentTime
                                lastBytesRead = totalBytesRead
                            }
                        }
                    }
                }
                onProgress(task, totalLength, totalLength, 100, 0)
            } catch (e: IOException) {
                e.printStackTrace()
                Log.d("DownloadCall", "download: ${e.message}")
            }
        }
    }


}
