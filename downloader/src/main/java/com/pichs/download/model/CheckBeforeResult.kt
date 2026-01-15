package com.pichs.download.model

/**
 * 创建前检查结果
 * 用于在创建任务前判断是否可以下载
 */
sealed class CheckBeforeResult {
    /** 允许下载 */
    object Allow : CheckBeforeResult()
    
    /** 无网络 */
    object NoNetwork : CheckBeforeResult()
    
    /** 仅WiFi模式，当前无WiFi */
    object WifiOnly : CheckBeforeResult()
    
    /** 需要用户确认 */
    data class NeedConfirmation(val estimatedSize: Long) : CheckBeforeResult()
    
    /** 用户控制模式，由使用端判断阈值 */
    @Deprecated("框架已支持阈值判断，使用 cellularThreshold 配置替代，此类型不再返回")
    data class UserControlled(val estimatedSize: Long) : CheckBeforeResult()
}
