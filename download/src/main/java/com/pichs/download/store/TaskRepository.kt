package com.pichs.download.store

import android.content.Context
import com.pichs.download.model.DownloadTask
import com.pichs.download.store.db.DownloadDatabase
import com.pichs.download.store.db.DownloadEntity

internal class TaskRepository(context: Context) {
    private val dao = DownloadDatabase.get(context).dao()
    private val chunkDao = DownloadDatabase.get(context).chunkDao()

    suspend fun save(task: DownloadTask) = dao.upsert(DownloadEntity.fromModel(task))

    suspend fun getAll(): List<DownloadTask> = dao.getAll().map { it.toModel() }

    suspend fun delete(id: String) {
        dao.delete(id)
        chunkDao.deleteByTask(id)
    }

    suspend fun deleteByUrl(url: String) = dao.deleteByUrl(url)

    suspend fun getById(id: String): DownloadTask? = dao.getById(id)?.toModel()

    suspend fun clear() {
        dao.clear()
        chunkDao.deleteByTask("") // 清空所有分片
    }

}
