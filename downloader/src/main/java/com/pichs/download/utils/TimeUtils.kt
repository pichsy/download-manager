package com.pichs.download.utils

import java.util.concurrent.atomic.AtomicInteger

/**
 * 时间工具类
 * 提供高精度时间戳，解决批量创建任务时排序不稳定问题
 */
object TimeUtils {

    // 序列号计数器 (0-999)
    private val counter = AtomicInteger(0)

    /**
     * 获取当前微秒级时间戳
     * 格式: 毫秒时间戳 * 1000 + 序列号(0-999)
     * 
     * 优势:
     * 1. 保持了挂钟时间的语义（高位是毫秒）
     * 2. 保证了同毫秒内的严格单调递增（低位是序列）
     * 3. Long类型可以直接存储，不溢出
     */
    fun currentMicros(): Long {
        val millis = System.currentTimeMillis()
        val seq = counter.getAndIncrement() % 1000
        // 确保 seq 是正数
        val positiveSeq = if (seq < 0) seq + 1000 else seq
        return millis * 1000 + positiveSeq
    }

    /**
     * 将微秒时间戳转回毫秒
     * 用于显示日期等场景
     */
    fun toMillis(micros: Long): Long {
        return micros / 1000
    }
}
