package com.pichs.download.breakpoint

/**
 * 任务的缓存
 */
object DownloadBreakPointManger {
    const val TABLE_NAME_BREAK_POINT = "xp_download_break_point_info"
    private fun getDao() = DownloadBreakPointDatabase.database.breakPointDao()
    fun upsert(data: DownloadBreakPointData) = getDao().upsert(data)
    fun queryByTaskId(taskId: String) = getDao().queryByTaskId(taskId)
    fun queryByUrl(url: String) = getDao().queryByUrl(url)
    fun deleteByTaskId(taskId: String) = getDao().deleteByTaskId(taskId)
    fun queryAll() = getDao().queryAll()
    fun deleteAll() = getDao().deleteAll()
}