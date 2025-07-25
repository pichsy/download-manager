package com.pichs.download.utils

import android.R.attr.tag

/**
 * 任务Id工具类，用于生成任务id
 */
object TaskIdUtils {

    /**
     * 生成任务id，规则：md5(url+filePath+fileName+tag+当前时间戳)
     */
    fun generateTaskId(url: String?, filePath: String?, fileName: String?, tag: String): String {
        // 搞个md5
        // 拼接字符串，如果为null则拼接空字符串""
        return MD5Utils.md5(
            StringBuilder()
                .append(url ?: "")
                .append(filePath ?: "")
                .append(fileName ?: "")
                .append(tag)
                .toString()
        ).lowercase()
    }

    fun generateTaskId(
        url: String?,
    ): String {
        return MD5Utils.md5(url ?: "").lowercase()
    }

}