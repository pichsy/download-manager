package com.pichs.download.model

/**
 * 常用下载任务排序。
 */
object DownloadTaskOrder {

    /** 优先级降序，同优先级按创建时间降序。 */
    val PRIORITY: Comparator<DownloadTask> =
        compareByDescending<DownloadTask> { it.priority }
            .thenByDescending { it.createTime }

    /** 创建时间降序（新的在前）。 */
    val RECENT: Comparator<DownloadTask> =
        compareByDescending { it.createTime }

    /** 更新时间降序。用于下载中列表会因进度更新导致行跳动。 */
    val UPDATED: Comparator<DownloadTask> =
        compareByDescending { it.updateTime }
}
