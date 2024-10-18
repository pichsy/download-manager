package com.pichs.download.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 分块下载的缓存，断点续传
 */
@Entity(tableName = "download_block_info")
data class BlockData(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,
    var taskId: String,
    var url: String,
    var path: String,
    var name: String
)