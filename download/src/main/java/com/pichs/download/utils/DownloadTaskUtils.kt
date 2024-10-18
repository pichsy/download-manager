package com.pichs.download.utils

/**
 * 任务Id工具类，用于生成任务id
 */
object DownloadTaskUtils {

    /**
     * 生成任务id，规则：md5(url+filePath+fileName+当前时间戳)
     */
    fun generateTaskId(url: String?, filePath: String?, fileName: String?): String {
        // 搞个md5
        return MD5Utils.md5((url + filePath + fileName) + System.currentTimeMillis().toString()).lowercase()
    }

}