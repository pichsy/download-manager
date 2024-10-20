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
    fun upsert(downloadBreakPoint: DownloadBreakPointData): Long

    @Query("SELECT * FROM `${TABLE_NAME_BREAK_POINT}` WHERE taskId = :taskId")
    fun queryByTaskId(taskId: String): DownloadBreakPointData?

    @Query("SELECT * FROM `${TABLE_NAME_BREAK_POINT}` WHERE url = :url")
    fun queryByUrl(url: String): MutableList<DownloadBreakPointData>?

    @Query("DELETE FROM `${TABLE_NAME_BREAK_POINT}` WHERE taskId = :taskId")
    fun deleteByTaskId(taskId: String)

    @Query("SELECT * FROM `${TABLE_NAME_BREAK_POINT}`")
    fun queryAll(): MutableList<DownloadBreakPointData>?

    @Query("DELETE FROM `${TABLE_NAME_BREAK_POINT}`")
    fun deleteAll()

}