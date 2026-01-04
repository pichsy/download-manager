package com.pichs.download.model

import androidx.annotation.Keep

/**
 * 流量提醒模式
 */
@Keep
enum class CellularPromptMode {
    /** 每次提醒（框架通过回调弹窗） */
    ALWAYS,
    /** 不再提醒（直接放行） */
    NEVER,
    /** 交给用户（检查 markCellularDownloadAllowed 状态，由使用端控制） */
    USER_CONTROLLED
}

/**
 * 网络下载配置
 */
@Keep
data class NetworkDownloadConfig(
    /** 是否仅 WiFi 下载 */
    val wifiOnly: Boolean = false,
    /** 流量提醒模式 */
    val cellularPromptMode: CellularPromptMode = CellularPromptMode.ALWAYS,
    /** 创建前检查 */
    val checkBeforeCreate: Boolean = false,
    /** 创建后检查 */
    val checkAfterCreate: Boolean = true
)
