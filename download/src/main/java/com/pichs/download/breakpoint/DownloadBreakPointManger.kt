package com.pichs.download.breakpoint

import com.pichs.download.utils.DownloadLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 任务的缓存
 */
object DownloadBreakPointManger {
    const val TABLE_NAME_BREAK_POINT = "xp_download_break_point_info"
    private fun getDao() = DownloadBreakPointDatabase.database.breakPointDao()
    suspend fun upsert(data: DownloadBreakPointData) = withContext(Dispatchers.IO) {
        try {
            getDao().upsert(data)
        } catch (e: Exception) {
            -1L
        }
    }

    suspend fun queryByTaskId(taskId: String) = withContext(Dispatchers.IO) {
        try {
            getDao().queryByTaskId(taskId)
        } catch (e: Exception) {
            DownloadLog.e(e) { "Exception queryByTaskId:" }
            null
        }
    }

    suspend fun queryByUrl(url: String) = withContext(Dispatchers.IO) {
        try {
            getDao().queryByUrl(url)
        } catch (e: Exception) {
            DownloadLog.e(e) { "Exception queryByUrl:" }
            null
        }
    }

    suspend fun deleteByTaskId(taskId: String) = withContext(Dispatchers.IO) {
        try {
            getDao().deleteByTaskId(taskId)
        } catch (e: Exception) {
            DownloadLog.e(e) { "Exception deleteByTaskId:" }
        }
    }

    suspend fun queryAll() = withContext(Dispatchers.IO) {
        try {
            getDao().queryAll()
        } catch (e: Exception) {
            DownloadLog.e(e) { "Exception queryAll:" }
            mutableListOf()
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        try {
            getDao().deleteAll()
        } catch (e: Exception) {
            DownloadLog.e(e) { "Exception deleteAll:" }
        }
    }



    suspend fun queryAllTasksByStatus(status: Int): MutableList<DownloadBreakPointData>? {
        return withContext(Dispatchers.IO) {
            try {
                getDao().queryAll().orEmpty().filter { it.status == status }.toMutableList()
            } catch (e: Exception) {
                DownloadLog.e(e) { "Exception queryAllTasksByStatus:" }
                mutableListOf()
            }
        }
    }

    suspend fun queryAllTasksIgnoreStatus(status: Int): MutableList<DownloadBreakPointData>? {
        return withContext(Dispatchers.IO) {
            try {
                getDao().queryAllTaskIgnoreStatus(status)
            } catch (e: Exception) {
                DownloadLog.e(e) { "Exception queryAllTasksIgnoreStatus:" }
                mutableListOf()
            }
        }
    }
}