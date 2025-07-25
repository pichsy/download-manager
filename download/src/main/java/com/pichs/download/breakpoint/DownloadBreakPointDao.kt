package com.pichs.download.breakpoint

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.pichs.download.breakpoint.DownloadBreakPointManger.TABLE_NAME_BREAK_POINT

/**
 * 任务的缓存
 */
@Dao
interface DownloadBreakPointDao {

    @Upsert
    suspend fun upsert(downloadBreakPoint: DownloadBreakPointData): Long

    @Query("SELECT * FROM `${TABLE_NAME_BREAK_POINT}` WHERE taskId = :taskId")
    suspend fun queryByTaskId(taskId: String): DownloadBreakPointData?

    @Query("SELECT * FROM `${TABLE_NAME_BREAK_POINT}` WHERE url = :url")
    suspend fun queryByUrl(url: String): MutableList<DownloadBreakPointData>?

    @Query("DELETE FROM `${TABLE_NAME_BREAK_POINT}` WHERE taskId = :taskId")
    suspend fun deleteByTaskId(taskId: String)

    @Query("SELECT * FROM `${TABLE_NAME_BREAK_POINT}`")
    suspend fun queryAll(): MutableList<DownloadBreakPointData>?

    @Query("DELETE FROM `${TABLE_NAME_BREAK_POINT}`")
    suspend fun deleteAll()

    @Query("SELECT * FROM `${TABLE_NAME_BREAK_POINT}` WHERE status = :status ORDER BY updateTime DESC")
    suspend fun queryAllTaskByStatus(status: Int = -1): MutableList<DownloadBreakPointData>?

    @Query("SELECT * FROM `${TABLE_NAME_BREAK_POINT}` WHERE status != :status ORDER BY updateTime DESC")
    suspend fun queryAllTaskIgnoreStatus(status: Int = -1): MutableList<DownloadBreakPointData>?
}