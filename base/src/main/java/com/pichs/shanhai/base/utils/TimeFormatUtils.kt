package com.pichs.shanhai.base.utils

import android.annotation.SuppressLint
import java.text.SimpleDateFormat
import java.util.*

/**
 * @author wubo
 */
object TimeFormatUtils {

    /**
     * 格式化
     * @param seconds 秒，多少秒
     * eg : 11:59:59 // 十一点
     * or   10:10 // 10分钟10秒
     */
    fun formatTimeSeconds(seconds: Int): String {
        var second = seconds
        var min: Int = 0
        var hour: Int = 0
        if (second >= 60) {
            min = second / 60
            second %= 60
        }
        if (min >= 60) {
            hour = min / 60
            min %= 60
        }
        val timeBuilder = StringBuffer()
        if (hour >= 10) {
            timeBuilder.append(hour).append(":")
        } else if (hour > 0) {
            timeBuilder.append("0").append(hour).append(":")
        }

        if (min >= 10) {
            timeBuilder.append(min).append(":")
        } else if (min > 0) {
            timeBuilder.append("0").append(min).append(":")
        } else {
            timeBuilder.append("00:")
        }
        if (second >= 10) {
            timeBuilder.append(second)
        } else {
            timeBuilder.append("0").append(second)
        }
        return timeBuilder.toString()
    }

    /**
     * [time] 时间单位 ms 毫秒
     *
     * eg : 11:59:59 // 十一点
     * or   10:10 // 10分钟10秒
     */
    @JvmStatic
    fun formatTimeMillSeconds(time: Long): String {
        val second: Int = (time / 1000).toInt()
        return formatTimeSeconds(second)
    }

    @JvmStatic
    fun formatTimeSecondsChinese(millSeconds: Long): String {
        var second = millSeconds / 1000L
        var min = 0L
        var hour = 0L
        if (second >= 60) {
            min = second / 60L
            second %= 60
        }
        if (min >= 60) {
            hour = min / 60
            min %= 60
        }
        val timeBuilder = StringBuffer()
        if (hour > 0) {
            timeBuilder.append(hour).append("小时")
        }
        if (min > 0) {
            timeBuilder.append(min).append("分钟")
        }
        if (second > 0) {
            timeBuilder.append(second).append("秒钟")
        }
        return timeBuilder.toString()
    }


    /**
     * [time] 时间单位 s 毫秒
     *      倒计时： 3天
     *    小于1天则显示  11:59:59 // 十一点
     *      00:00:10 // 10分钟10秒
     */
    @JvmStatic
    fun formatTimeCountdown(time: Int): String {
        var second = time

        if (time >= 86400) {
            // 说明还有1天
            return "${(time / 86400) + 1}天"
        }

        var min: Int = 0
        var hour: Int = 0

        if (second >= 60) {
            min = second / 60
            second %= 60
        }
        if (min >= 60) {
            hour = min / 60
            min %= 60
        }

        val timeBuilder = StringBuffer()
        if (hour >= 10) {
            timeBuilder.append(hour).append(":")
        } else {
            timeBuilder.append("0").append(hour).append(":")
        }

        if (min >= 10) {
            timeBuilder.append(min).append(":")
        } else {
            timeBuilder.append("0").append(min).append(":")
        }
        if (second >= 10) {
            timeBuilder.append(second)
        } else {
            timeBuilder.append("0").append(second)
        }
        return timeBuilder.toString()
    }

    @SuppressLint("SimpleDateFormat")
    val dataFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    @SuppressLint("SimpleDateFormat")
    val dataFormatMills = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    @SuppressLint("SimpleDateFormat")
    val dataFormatLineYearMonthDay = SimpleDateFormat("yyyy-MM-dd")

    @SuppressLint("SimpleDateFormat")
    val hourMinuteTimeFormat = SimpleDateFormat("HH:mm")

    /**
     * yyyy-MM-dd HH:mm:ss
     */
    fun formatTime(timeStamp: Long): String {
        return dataFormat.format(timeStamp)
    }

    /**
     * yyyy-MM-dd HH:mm:ss.SSS
     */
    fun formatTimeSSS(timeStamp: Long): String {
        return dataFormatMills.format(timeStamp)
    }

    /**
     * @param time yyyy-MM-dd HH:mm:ss.SSS
     * @return 返回时间戳
     */
    fun decodeTimeSSS(time: String): Long {
        return dataFormatMills.parse(time)?.time ?: 0L
    }

    /**
     * 获取 2000-10-10 12:00:00
     */
    fun formatTimeShotLine(timeStamp: Long): String {
        return dataFormat.format(timeStamp)
    }

    /**
     * 获取 2000-10-10
     */
    fun formatTimeShotLineYearMonth(timeMills: Long): String {
        return dataFormatLineYearMonthDay.format(timeMills)
    }

    /**
     * 获取 12:12
     */
    fun formatHourMinuteTime(timeMills: Long): String {
        return hourMinuteTimeFormat.format(timeMills)
    }

    /**
     * 将 12:12 解析为 今日的时间戳
     */
    fun parseHourMinuteToTimeMills(time: String): Long {
        // 获取当前时间
        val arr = time.split(":")
        if (arr.size != 2) {
            return -1L
        }
        val hour = arr[0].toIntOrNull() ?: 0
        val minute = arr[1].toIntOrNull() ?: 0
        if (hour == 0 && minute == 0) {
            return -1L
        }
        val calendar = Calendar.getInstance()
        // 使用calendar获取当前的年月日
        calendar.time = Date()
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

}