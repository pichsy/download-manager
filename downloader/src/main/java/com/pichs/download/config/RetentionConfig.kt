package com.pichs.download.config

/**
 * 保留策略配置
 * 用于控制已完成/失败任务的自动清理行为
 */
data class RetentionConfig(
    /**
     * 已完成任务的保留天数（超过此天数的任务会被清理）
     * 默认 30 天
     */
    val keepCompletedDays: Int = 30,
    
    /**
     * 失败任务的保留天数
     * 默认 7 天
     */
    val keepFailedDays: Int = 7,
    
    /**
     * 已取消任务的保留天数
     * 默认 3 天
     */
    val keepCancelledDays: Int = 3,
    
    /**
     * 保留最近N个已完成任务（按更新时间排序）
     * 默认 100 个
     */
    val keepLatestCompleted: Int = 100,
    
    /**
     * 保留最近N个失败任务
     * 默认 20 个
     */
    val keepLatestFailed: Int = 20,
    
    /**
     * 保护期（小时）
     * 刚完成的任务在此期间内不会被清理，确保有足够时间完成后续操作（如安装APK）
     * 默认 24 小时
     */
    val protectionPeriodHours: Int = 24,
    
    /**
     * 低存储空间时单次最多删除的任务数
     * 默认 10 个
     */
    val maxTasksToDeleteOnLowStorage: Int = 10
)


/**
 * 保留策略统计信息
 */
data class RetentionStats(
    /** 总任务数 */
    val totalTasks: Int,
    /** 已完成任务数 */
    val completedTasks: Int,
    /** 失败任务数 */
    val failedTasks: Int,
    /** 已取消任务数 */
    val cancelledTasks: Int,
    /** 总大小（字节） */
    val totalSize: Long,
    /** 最老任务的创建时间 */
    val oldestTask: Long
)
