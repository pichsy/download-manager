package com.pichs.download.store

import android.content.Context
import com.pichs.download.model.DownloadTask
import com.pichs.download.store.db.DownloadDatabase
import com.pichs.download.store.db.DownloadEntity

internal class TaskRepository(context: Context) {
    private val dao = DownloadDatabase.get(context).dao()

    suspend fun save(task: DownloadTask) = dao.upsert(DownloadEntity.fromModel(task))

    suspend fun getAll(): List<DownloadTask> = dao.getAll().map { it.toModel() }
}
