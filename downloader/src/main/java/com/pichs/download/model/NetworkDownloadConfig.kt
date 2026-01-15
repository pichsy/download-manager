package com.pichs.download.model

import androidx.annotation.Keep

/**
 * 流量阈值常量
 */
object CellularThreshold {
    /** 不提醒：直接使用流量下载 */
    const val NEVER_PROMPT = Long.MAX_VALUE
    /** 每次提醒：任何流量下载都弹窗 */
    const val ALWAYS_PROMPT = 0L
}

/**
 * 网络下载配置
 */
@Keep
data class NetworkDownloadConfig(
    /** 是否仅 WiFi 下载 */
    val wifiOnly: Boolean = false,
    /** 
     * 流量提醒阈值（字节）
     * - Long.MAX_VALUE (CellularThreshold.NEVER_PROMPT): 不提醒
     * - 0L (CellularThreshold.ALWAYS_PROMPT): 每次都提醒
     * - 其他正值: 超过此大小时提醒
     */
    val cellularThreshold: Long = CellularThreshold.ALWAYS_PROMPT,
    /** 创建前检查 */
    val checkBeforeCreate: Boolean = false,
    /** 创建后检查 */
    val checkAfterCreate: Boolean = true
)

// 保留旧枚举用于兼容，标记废弃
@Deprecated("使用 cellularThreshold 替代", ReplaceWith("CellularThreshold"))
@Keep
enum class CellularPromptMode {
    ALWAYS, NEVER, USER_CONTROLLED
}
