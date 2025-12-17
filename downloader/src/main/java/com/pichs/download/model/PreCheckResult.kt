package com.pichs.download.model

/**
 * 预检查结果
 * 用于在创建任务前判断是否可以下载
 */
sealed class PreCheckResult {
    /** 允许下载 */
    object Allow : PreCheckResult()
    
    /** 无网络 */
    object NoNetwork : PreCheckResult()
    
    /** 仅WiFi模式，当前无WiFi */
    object WifiOnly : PreCheckResult()
    
    /** 需要用户确认 */
    data class NeedConfirmation(val estimatedSize: Long) : PreCheckResult()
    
    /** 用户控制模式，由使用端判断阈值 */
    data class UserControlled(val estimatedSize: Long) : PreCheckResult()
}
