package com.pichs.download.demo

import com.pichs.xbase.utils.GsonUtils

/**
 * 下载任务扩展信息
 * 用于存储在 DownloadTask.extras 中的 JSON 数据
 */
data class ExtraMeta(
    val name: String? = null,
    val packageName: String? = null,
    val versionCode: Long? = null,
    val icon: String? = null,
    val size: Long? = null,
    val install_count: String? = null,
    val description: String? = null,
    val update_time: String? = null,
    val version_name: String? = null,
    val developer: String? = null,
    val registration_no: String? = null,
    val categories: List<String>? = null,
    val tags: List<String>? = null,
    val screenshots: List<String>? = null
) {
    companion object {
        /**
         * 从 JSON 字符串解析 ExtraMeta
         */
        fun fromJson(json: String?): ExtraMeta? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                GsonUtils.fromJson(json, ExtraMeta::class.java)
            }.getOrNull()
        }
    }
    
    /**
     * 转换为 JSON 字符串
     */
    fun toJson(): String {
        return GsonUtils.toJson(this)
    }
}
