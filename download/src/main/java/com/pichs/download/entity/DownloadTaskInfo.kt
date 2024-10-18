package com.pichs.download.entity


data class DownloadTaskInfo(
    var id: String = "",
    var isSingleDownload: Boolean = false,
    var url: String? = "",
    var filePath: String? = null,
    var fileName: String? = null,
    var currentLength: Long? = 0,
    var totalLength: Long? = 0,
    var progress: Int? = 0,
    // 0：等待下载，1：下载中，2：暂停，3：完成，4：失败
    var status: Int? = 0
) {

    /**
     * 获取当前下载长度
     */
    fun getCurrentLength(): Long {
        return currentLength ?: 0
    }

    /**
     * 获取总长度
     */
    fun getTotalLength(): Long {
        return totalLength ?: 0
    }

    /**
     * progress
     */
    fun getProgress(): Int {
        return progress ?: 0
    }

    /**
     * status
     */
    fun getStatus(): Int {
        return status ?: 0
    }


}


