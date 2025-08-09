package com.pichs.download.utils

import com.pichs.download.config.DownloadConfig
import com.pichs.download.core.DownloadManager
import com.pichs.download.internal.HeaderData
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object OkHttpHelper {

    @Volatile
    private var okHttpClient: OkHttpClient? = null

    @Synchronized
    fun rebuildClient(cfg: DownloadConfig) {
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(cfg.readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(cfg.writeTimeoutSec, TimeUnit.SECONDS)
            .build()
    }

    private fun client(): OkHttpClient {
        val c = okHttpClient
        if (c != null) return c
        rebuildClient(DownloadManager.currentConfig())
        return okHttpClient!!
    }

    /**
     * 执行请求
     */
    fun execute(request: Request): Response {
        return client().newCall(request).execute()
    }


    /**
     * 获取文件总长度及相关头（HEAD 不可用时回退 Range GET）
     */
    fun getFileTotalLengthFromUrl(url: String): HeaderData {
        // 优先 HEAD
        val headReq = Request.Builder().url(url).head().header("Accept-Encoding", "identity").build()
        try {
            execute(headReq).use { response ->
                if (response.isSuccessful) {
                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    val contentType = response.header("Content-Type")
                    val eTag = response.header("ETag")
                    val acceptRanges = response.header("Accept-Ranges")
                    val lastModified = response.header("Last-Modified")
                    return HeaderData(contentLength, contentType, eTag, acceptRanges, lastModified)
                }
            }
        } catch (_: Exception) { /* fallback */ }

        // 回退：用 Range GET 只取首字节，解析 Content-Range 中的总长度
        val getReq = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        execute(getReq).use { resp ->
            if (!resp.isSuccessful && resp.code != 206 && resp.code != 200) {
                throw IOException("Unexpected code $resp")
            }
            // Content-Range: bytes 0-0/12345
            val contentRange = resp.header("Content-Range")
            val totalFromRange = contentRange?.substringAfter('/')?.toLongOrNull() ?: -1L
            val contentLength = if (totalFromRange > 0) totalFromRange else resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val contentType = resp.header("Content-Type")
            val eTag = resp.header("ETag")
            val acceptRanges = resp.header("Accept-Ranges") ?: if (resp.code == 206) "bytes" else null
            val lastModified = resp.header("Last-Modified")
            return HeaderData(
                contentLength = contentLength,
                contentType = contentType,
                eTag = eTag,
                acceptRanges = acceptRanges,
                lastModified = lastModified,
            )
        }
    }

}