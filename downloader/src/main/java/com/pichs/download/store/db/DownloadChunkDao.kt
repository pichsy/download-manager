package com.pichs.download.store.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
internal interface DownloadChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<DownloadChunkEntity>)

    @Update
    suspend fun update(chunk: DownloadChunkEntity)

    @Query("SELECT * FROM download_chunks WHERE taskId = :taskId ORDER BY `index`")
    suspend fun getByTask(taskId: String): List<DownloadChunkEntity>

    @Query("DELETE FROM download_chunks WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)
}


