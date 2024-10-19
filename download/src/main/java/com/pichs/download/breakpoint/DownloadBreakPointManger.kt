package com.pichs.download.breakpoint

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 任务的缓存
 */
object DownloadBreakPointManger {
    const val TABLE_NAME_BREAK_POINT = "xp_download_break_point_info"
    private fun getDao() = DownloadBreakPointDatabase.database.breakPointDao()
    suspend fun upsert(data: DownloadBreakPointData) = withContext(Dispatchers.IO) {
        getDao().upsert(data)
    }

    suspend fun queryByTaskId(taskId: String) = withContext(Dispatchers.IO) {
        getDao().queryByTaskId(taskId)
    }

    suspend fun queryByUrl(url: String) = withContext(Dispatchers.IO) {
        getDao().queryByUrl(url)
    }

    suspend fun deleteByTaskId(taskId: String) = withContext(Dispatchers.IO) {
        getDao().deleteByTaskId(taskId)
    }

    suspend fun queryAll() = withContext(Dispatchers.IO) {
        getDao().queryAll()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        getDao().deleteAll()
    }
}