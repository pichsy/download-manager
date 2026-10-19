package com.pichs.download.store.db

import androidx.annotation.Keep
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Keep
@Dao
internal interface DownloadChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<DownloadChunkEntity>)

    @Update
    suspend fun update(chunk: DownloadChunkEntity)

    @Query("SELECT * FROM download_chunks WHERE taskId = :taskId ORDER BY `index`")
    suspend fun getByTask(taskId: String): List<DownloadChunkEntity>

    @Query("SELECT * FROM download_chunks WHERE taskId = :taskId AND `index` = :index LIMIT 1")
    suspend fun getByTaskAndIndex(taskId: String, index: Int): DownloadChunkEntity?

    @Query("DELETE FROM download_chunks WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)

    @Query("DELETE FROM download_chunks")
    suspend fun clear()
}


