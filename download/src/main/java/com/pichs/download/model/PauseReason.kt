package com.pichs.download.model

/**
 * 下载暂停原因枚举
 * 用于区分不同的暂停情况，实现智能的自动恢复机制
 */
enum class PauseReason {
    /**
     * 用户手动暂停
     * 需要用户手动恢复，不会自动恢复
     */
    USER_MANUAL,
    
    /**
     * 网络异常暂停
     * 网络恢复后会自动恢复下载
     */
    NETWORK_ERROR,
    
    /**
     * 电量不足暂停
     * 电量恢复后会自动恢复下载
     */
    BATTERY_LOW,
    
    /**
     * 存储空间不足暂停
     * 存储空间释放后会自动恢复下载
     */
    STORAGE_FULL,
    
    /**
     * 系统资源不足暂停
     * 系统资源恢复后会自动恢复下载
     */
    SYSTEM_RESOURCE_LOW
}
