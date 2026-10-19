package com.pichs.download.utils

import com.pichs.download.config.DownloadConfig
import com.pichs.download.core.DownloadManager
import com.pichs.download.internal.HeaderData
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object OkHttpHelper {

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var okHttpClient: OkHttpClient? = null

    @Synchronized
    fun rebuildClient(cfg: DownloadConfig) {
        val builder = OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(cfg.readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(cfg.writeTimeoutSec, TimeUnit.SECONDS)

        // 使用端拦截器先执行，可覆盖下面的默认头
        cfg.requestInterceptors.toList().forEach { builder.addInterceptor(it) }
        builder.addInterceptor { chain ->
            chain.proceed(applyDefaultHeaders(chain.request(), cfg))
        }
        okHttpClient = builder.build()
    }

    /**
     * 请求级 headers 优先，其次全局 headers，最后是默认 UA/Referer/Accept。
     */
    private fun applyDefaultHeaders(original: Request, cfg: DownloadConfig): Request {
        val builder = original.newBuilder()

        cfg.globalHeaders.forEach { (name, value) ->
            if (original.header(name) == null) {
                builder.header(name, value)
            }
        }

        val url = original.url
        if (original.header("User-Agent") == null &&
            cfg.globalHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }
        ) {
            builder.header("User-Agent", DEFAULT_USER_AGENT)
        }
        if (original.header("Referer") == null &&
            cfg.globalHeaders.keys.none { it.equals("Referer", ignoreCase = true) }
        ) {
            builder.header("Referer", "${url.scheme}://${url.host}/")
        }
        if (original.header("Accept") == null &&
            cfg.globalHeaders.keys.none { it.equals("Accept", ignoreCase = true) }
        ) {
            builder.header("Accept", "*/*")
        }
        return builder.build()
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
     * 创建可取消的 Call
     */
    fun newCall(request: Request): Call = client().newCall(request)

    /**
     * 获取文件总长度及相关头（HEAD 不可用时回退 Range GET）
     */
    fun getFileTotalLengthFromUrl(url: String): HeaderData {
        return getFileTotalLengthFromUrl(url, emptyMap())
    }

    /**
     * 获取文件总长度及相关头。
     * 探测请求会携带任务级请求头（如 Cookie/Authorization），否则鉴权资源会探测失败。
     * HEAD 拿不到有效长度时回退为 `Range: bytes=0-0` GET。
     * @param onCallCreated 探测请求的 Call 创建回调，供引擎在暂停时取消阻塞中的探测
     */
    fun getFileTotalLengthFromUrl(
        url: String,
        headers: Map<String, String>,
        onCallCreated: ((Call) -> Unit)? = null
    ): HeaderData {
        val headRequest = Request.Builder()
            .url(url)
            .head()
            .header("Accept-Encoding", "identity")
        applyTaskHeaders(headRequest, headers)

        var headCall: Call? = null
        try {
            val call = newCall(headRequest.build())
            headCall = call
            onCallCreated?.invoke(call)
            call.execute().use { response ->
                if (response.isSuccessful) {
                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    // Content-Length: 0 可能来自不支持 HEAD 的服务器，继续用 GET 确认
                    if (contentLength > 0) {
                        return HeaderData(
                            contentLength = contentLength,
                            contentType = response.header("Content-Type"),
                            eTag = response.header("ETag"),
                            acceptRanges = response.header("Accept-Ranges"),
                            lastModified = response.header("Last-Modified"),
                        )
                    }
                }
            }
        } catch (e: IOException) {
            if (headCall?.isCanceled() == true || isCancellation(e)) throw e
            // HEAD 不受支持时继续用 GET 探测
        } catch (_: Exception) {
            // 其他异常同样回退到 GET 探测
        }

        val getRequest = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .get()
        applyTaskHeaders(getRequest, headers)

        val getCall = newCall(getRequest.build())
        onCallCreated?.invoke(getCall)
        getCall.execute().use { response ->
            val contentType = response.header("Content-Type")
            val eTag = response.header("ETag")
            val lastModified = response.header("Last-Modified")

            if (response.code == 416) {
                // 必须是 bytes */N 才能确认文件大小；缺失时不能当作空文件
                val total = com.pichs.download.core.parseUnsatisfiedRangeTotal(
                    response.header("Content-Range")
                ) ?: throw IOException(
                    "416 响应缺少合法的 Content-Range: ${response.header("Content-Range")}"
                )
                return HeaderData(
                    contentLength = total,
                    contentType = contentType,
                    eTag = eTag,
                    acceptRanges = "none",
                    lastModified = lastModified,
                )
            }

            if (!response.isSuccessful) {
                throw IOException("Unexpected code $response")
            }

            val contentRange = response.header("Content-Range")
            val totalFromRange = contentRange?.substringAfter('/')?.toLongOrNull() ?: -1L
            val bodyLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            // 206 只信任 Content-Range 中的总长度；未知就是 -1（未知长度单流下载）
            val contentLength = if (response.code == 206) totalFromRange else bodyLength
            // 200 说明服务端忽略了 Range，后续强制走单流
            val acceptRanges = if (response.code == 200) {
                "none"
            } else {
                response.header("Accept-Ranges") ?: if (response.code == 206) "bytes" else null
            }

            return HeaderData(
                contentLength = contentLength,
                contentType = contentType,
                eTag = eTag,
                acceptRanges = acceptRanges,
                lastModified = lastModified,
            )
        }
    }

    private fun isCancellation(e: IOException): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            // SocketTimeoutException 也是 InterruptedIOException，但它属于可回退的网络错误，
            // 不能被当成用户取消，否则 HEAD 超时会让整个探测直接失败。
            if (cause is java.net.SocketTimeoutException) return false
            if (cause is java.io.InterruptedIOException) return true
            cause = cause.cause
        }
        return false
    }

    private fun applyTaskHeaders(builder: Request.Builder, headers: Map<String, String>) {
        headers.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true) &&
                !key.equals("If-Range", ignoreCase = true) &&
                !key.equals("Accept-Encoding", ignoreCase = true)
            ) {
                builder.header(key, value)
            }
        }
    }
}
