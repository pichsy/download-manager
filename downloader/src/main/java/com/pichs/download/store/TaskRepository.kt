package com.pichs.download.store

import android.content.Context
import androidx.annotation.Keep
import androidx.room.withTransaction
import com.pichs.download.model.DownloadTask
import com.pichs.download.store.db.DownloadDatabase
import com.pichs.download.store.db.DownloadEntity
import com.pichs.download.store.db.DownloadMeta
import com.pichs.download.store.db.DownloadMetaEntity
import com.pichs.download.store.db.HeaderCodec
import com.pichs.download.store.db.toModel
@Keep
internal class TaskRepository(context: Context) {
    private val database = DownloadDatabase.get(context)
    private val dao = database.dao()
    private val chunkDao = database.chunkDao()
    private val metaDao = database.metaDao()

    suspend fun save(task: DownloadTask) = dao.upsert(DownloadEntity.fromModel(task))

    suspend fun getAll(): List<DownloadTask> = dao.getAll().map { it.toModel() }

    suspend fun delete(id: String) {
        database.withTransaction {
            dao.delete(id)
            chunkDao.deleteByTask(id)
            metaDao.delete(id)
        }
    }

    suspend fun deleteByUrl(url: String) {
        database.withTransaction {
            // 删除任务前先清理分片与元数据，避免孤儿行
            dao.getIdsByUrl(url).forEach { id ->
                chunkDao.deleteByTask(id)
                metaDao.delete(id)
            }
            dao.deleteByUrl(url)
        }
    }

    suspend fun getById(id: String): DownloadTask? = dao.getById(id)?.toModel()

    suspend fun saveMeta(taskId: String, meta: DownloadMeta) {
        metaDao.upsert(
            DownloadMetaEntity(
                taskId = taskId,
                headersEncoded = HeaderCodec.encode(meta.headers),
                eTag = meta.eTag,
                lastModified = meta.lastModified,
                totalSize = meta.totalSize,
                checksumType = meta.checksum?.type?.name,
                checksumValue = meta.checksum?.value,
                checksumOnFail = meta.checksum?.onFail?.name,
            )
        )
    }

    suspend fun getMeta(taskId: String): DownloadMeta? = metaDao.getById(taskId)?.toModel()

    suspend fun getAllMeta(): List<DownloadMeta> = metaDao.getAll().map { it.toModel() }

    /**
     * 删除没有对应任务的元数据，返回删除条数。
     * @param activeTaskIds 已创建但可能尚未落库的任务，避免与启动恢复并发时误删
     */
    suspend fun deleteOrphanMeta(activeTaskIds: Collection<String>): Int =
        if (activeTaskIds.isEmpty()) {
            metaDao.deleteAllOrphans()
        } else {
            metaDao.deleteOrphans(activeTaskIds.toList())
        }

    suspend fun clear() {
        database.withTransaction {
            dao.clear()
            chunkDao.clear()
            metaDao.clear()
        }
    }

}
