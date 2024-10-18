
package com.pichs.download.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

const val tableName = "xxx_download_break_point"
/**
 * 断点续传的缓存
 */
@Entity(tableName = tableName)
data class BreakPointData(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,
    var taskId: String,
    var url: String,
    var range: String,
    var path: String,
    var name: String
)
