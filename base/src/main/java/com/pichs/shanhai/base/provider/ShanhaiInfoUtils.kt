package com.pichs.shanhai.base.provider

import android.annotation.SuppressLint
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.pichs.xbase.utils.UiKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShanhaiInfoUtils {

    /**
     * 获取数据的数据
     */
    @SuppressLint("Range")
    suspend fun query(key: String, defaultValue: String? = null): String? {
        return withContext(Dispatchers.IO) {
            var cursor: Cursor? = null
            try {
                if (key.isEmpty()) return@withContext defaultValue
                val uri = Uri.parse("content://com.pichs.shanhai.content.provider/information")
                cursor = UiKit.getApplication().contentResolver.query(uri, arrayOf("key", "value"), "key = ?", arrayOf(key), null)
                if (cursor?.moveToNext() == true) {
                    return@withContext cursor.getString(cursor.getColumnIndex(key)) ?: defaultValue
                }
                return@withContext defaultValue
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    cursor?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            defaultValue
        }
    }

    suspend fun insertOrUpdate(key: String, value: String): Boolean {
        return withContext(Dispatchers.IO) {
            val cursor: Cursor? = null
            try {
                if (key.isEmpty()) return@withContext false
                val uri = Uri.parse("content://com.pichs.shanhai.content.provider/information")
                val contentValues = ContentValues()
                contentValues.put("key", key)
                contentValues.put("value", value)
                val insert = UiKit.getApplication().contentResolver.insert(uri, contentValues)
                return@withContext insert != null
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    cursor?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            false
        }
    }

    suspend fun delete(key: String): Boolean {
        return withContext(Dispatchers.IO) {
            val cursor: Cursor? = null
            try {
                if (key.isEmpty()) return@withContext false
                val uri = Uri.parse("content://com.pichs.shanhai.content.provider/information")
                val delete = UiKit.getApplication().contentResolver.delete(uri, "key = ?", arrayOf(key))
                return@withContext delete > 0
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    cursor?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            false
        }
    }

}