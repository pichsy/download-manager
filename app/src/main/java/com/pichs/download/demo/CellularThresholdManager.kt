package com.pichs.download.demo

import android.content.Context
import android.content.SharedPreferences

/**
 * 使用端流量阈值配置管理器
 * 用于批量下载前的智能拦截判断
 */
object CellularThresholdManager {
    
    private const val PREFS_NAME = "cellular_threshold_config"
    private const val KEY_THRESHOLD_MB = "threshold_mb"
    private const val KEY_SMART_MODE_ENABLED = "smart_mode_enabled"
    
    private var prefs: SharedPreferences? = null
    
    // 预设的阈值档位（MB）
    val thresholds = listOf(20, 50, 100, 200, 500, 1000)
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 是否启用智能提醒模式
     */
    var smartModeEnabled: Boolean
        get() = prefs?.getBoolean(KEY_SMART_MODE_ENABLED, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_SMART_MODE_ENABLED, value)?.apply()
        }
    
    /**
     * 获取阈值（MB）
     */
    var thresholdMB: Int
        get() = prefs?.getInt(KEY_THRESHOLD_MB, 500) ?: 500
        set(value) {
            prefs?.edit()?.putInt(KEY_THRESHOLD_MB, value)?.apply()
        }
    
    /**
     * 获取阈值（字节）
     */
    val thresholdBytes: Long
        get() = thresholdMB * 1024L * 1024L
    
    /**
     * 判断给定大小是否需要提醒
     * @param totalSizeBytes 总大小（字节）
     * @return true: 需要提醒，false: 不需要提醒（静默下载）
     */
    fun shouldPrompt(totalSizeBytes: Long): Boolean {
        if (!smartModeEnabled) {
            // 智能模式未启用，使用框架的配置
            return true
        }
        // 智能模式：超过阈值才提醒
        return totalSizeBytes >= thresholdBytes
    }
    
    /**
     * 格式化阈值显示
     */
    fun formatThreshold(mb: Int): String {
        return if (mb >= 1000) "${mb / 1000}GB" else "${mb}MB"
    }
}
