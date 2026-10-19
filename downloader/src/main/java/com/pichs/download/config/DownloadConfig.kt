package com.pichs.download.config

import androidx.annotation.Keep

@Keep
data class DownloadConfig(
    var maxConcurrentTasks: Int = 1,
    var connectTimeoutSec: Long = 60,
    var readTimeoutSec: Long = 60,
    var writeTimeoutSec: Long = 60,
    var allowMetered: Boolean = true,
    // 回调线程语义：是否强制在主线程派发监听
    var callbackOnMain: Boolean = true,
    // 默认下载目录（未指定 path 时使用）
    var defaultDownloadDirPath: String = "",
    // 分片参数：单个分片最小字节数（文件不足两倍该值时单线程）
    var minChunkSizeBytes: Long = 2L * 1024 * 1024,
    // 分片参数：单任务最大分片数
    var maxChunksPerTask: Int = 6,
    // 全局请求头；任务级 headers 优先
    var globalHeaders: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap(),
    // 全局 OkHttp 拦截器；探测与分片请求都会经过
    var requestInterceptors: MutableList<okhttp3.Interceptor> = java.util.concurrent.CopyOnWriteArrayList(),
    // 文件名解析扩展；返回 null/blank 时使用默认规则
    var fileNameResolver: FileNameResolver? = null,
    // 预留：校验相关（当前不启用实际校验逻辑）
    var checksum: Checksum? = null,
    // 预留：保留策略（用于自动清理已完成任务）
    var retention: Retention = Retention()
)

/**
 * 自定义文件名解析。
 * 在任务创建时调用，实现方可根据 URL、请求头或业务规则决定落盘文件名。
 */
@Keep
fun interface FileNameResolver {
    /**
     * @param url 下载地址
     * @param explicitName 调用方通过 fileName(...) 显式指定的文件名，可能为空
     * @return 自定义文件名；返回 null 或空串表示沿用默认解析
     */
    fun resolve(url: String, explicitName: String?): String?
}
@Keep
data class Checksum(
    val type: Type,
    val value: String,
    val onFail: OnFail = OnFail.Ignore
) {
    enum class Type { MD5, SHA256 }
    enum class OnFail { Ignore, Fail, Retry }
}
@Keep
data class Retention(
    // 保留天数；<=0 表示不按时间自动清理
    val keepDays: Int = 0,
    // 仅保留最近 N 条完成任务；<=0 表示不限制
    val keepLatestCompleted: Int = 0
)
