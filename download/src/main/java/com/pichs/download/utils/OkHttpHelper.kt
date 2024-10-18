package com.pichs.download.utils

import com.pichs.download.entity.HeaderData
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object OkHttpHelper {

    val okHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }


    /**
     * 执行请求
     */
    fun execute(request: Request): Response {
        return okHttpClient.newCall(request).execute()
    }


    /**
     * 获取文件总长度
     */
    fun getFileTotalLengthFromUrl(url: String): HeaderData {
        val request = Request.Builder()
            .url(url)
            .head()
            .build()
        val response = execute(request)
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
        val contentType = response.header("Content-Type")

        val headerData = HeaderData().apply {
            this.contentLength = contentLength
            this.contentType = contentType
        }

        return headerData
    }

}