package com.pichs.download.breakpoint

import android.provider.ContactsContract.CommonDataKinds.Im
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * 断点信息数据库操作
 */
@Dao
interface DownloadChunkDao {

    /**
     * 插入断点信息,如果存在则更新
     */
    @Upsert(entity = DownloadChunk::class)
    fun upsert(info: DownloadChunk): Long

    /**
     * 查询任务id对应的断点信息列表
     */
    @Query("SELECT * FROM `${DownloadChunkManager.TABLE_NAME_CHUNK}` WHERE taskId = :taskId ORDER BY chunkIndex ASC")
    fun queryByTaskId(taskId: String): MutableList<DownloadChunk>?

    /**
     * 删除任务id对应的断点信息
     */
    @Query("DELETE FROM `${DownloadChunkManager.TABLE_NAME_CHUNK}` WHERE taskId = :taskId")
    fun deleteByTaskId(taskId: String)

    /**
     * 删除所有断点信息
     */
    @Query("DELETE FROM `${DownloadChunkManager.TABLE_NAME_CHUNK}`")
    fun deleteAll()

    /**
     * 查询所有断点信息
     */
    @Query("SELECT * FROM `${DownloadChunkManager.TABLE_NAME_CHUNK}`")
    fun queryAll(): MutableList<DownloadChunk>?

}