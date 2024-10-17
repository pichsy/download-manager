package com.pichs.shanhai.base.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import com.pichs.shanhai.base.provider.sql.ShanhaiCursor
import com.pichs.shanhai.base.provider.sql.ShanhaiInfo
import com.pichs.shanhai.base.provider.sql.ShanhaiInfoSqlManager
import com.pichs.xbase.xlog.XLog

class ShanhaiProvider : ContentProvider() {

    companion object {
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH)

        init {
            uriMatcher.addURI("com.pichs.shanhai.content.provider", "information", 1)
        }

    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        return when (uriMatcher.match(uri)) {
            1 -> {
                val key = selectionArgs?.getOrNull(0) ?: ""
                XLog.d("山海：查询数据：有人访问了 cache数据库，查询：key=${key}")
                if (key.isEmpty()) {
                    XLog.d("山海：查询数据：有人访问了 cache数据库，喔没Key 还查个吊哦，返回空")
                    return null
                }
                val value = ShanhaiInfoSqlManager.query(key)
                return ShanhaiCursor(key = key, result = value?.value)
            }

            else -> {
                null
            }
        }
    }

    override fun getType(uri: Uri): String? {
        return "text/plain"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        when (uriMatcher.match(uri)) {
            1 -> {
                val key = values?.getAsString("key") ?: ""
                val value = values?.getAsString("value") ?: ""
                XLog.d("山海：插入数据：有人访问了 cache数据库，插入：key=${key} value=${value}")
                if (key.isEmpty()) {
                    XLog.d("山海：插入数据：有人访问了 cache数据库，喔没Key 还插个吊哦，返回空")
                    return null
                }
                val result = ShanhaiInfoSqlManager.insertOrUpdate(ShanhaiInfo(key = key, value = value))
                XLog.d("山海：插入数据：有人访问了 cache数据库，插入结果：result=${result}")
                return uri
            }

            else -> {
                return null
            }
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        when (uriMatcher.match(uri)) {
            1 -> {
                val key = selectionArgs?.getOrNull(0) ?: ""
                XLog.d("山海：删除数据：有人访问了 cache数据库，删除：key=${key}")
                if (key.isEmpty()) {
                    XLog.d("山海：删除数据：有人访问了 cache数据库，喔没Key 还删除个吊哦，返回空")
                    return 0
                }
                val result = ShanhaiInfoSqlManager.delete(key)
                XLog.d("山海：删除数据：有人访问了 cache数据库，删除结果：result=${result}")
                return result
            }

            else -> {
                return 0
            }
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        when (uriMatcher.match(uri)) {
            1 -> {
                val key = values?.getAsString("key") ?: ""
                val value = values?.getAsString("value") ?: ""
                XLog.d("山海：更新数据：有人访问了 cache数据库，更新：key=${key} value=${value}")
                if (key.isEmpty()) {
                    XLog.d("山海：更新数据：有人访问了 cache数据库，喔没Key 还更新个吊哦，返回空")
                    return 0
                }
                val result = ShanhaiInfoSqlManager.insertOrUpdate(ShanhaiInfo(key = key, value = value))
                XLog.d("山海：更新数据：有人访问了 cache数据库，更新结果：result=${result}")
                return result.toInt()
            }

            else -> {
                return 0
            }
        }
    }


}