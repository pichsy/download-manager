package com.pichs.shanhai.base.utils

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.pichs.xbase.utils.MD5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * 获取DPC应用的参数
 */
object DpcDataUtils {

    @SuppressLint("Range")
    suspend fun getString(context: Context, key: String, defaultValue: String? = null): String? {
        return withContext(Dispatchers.IO) {
            var cursor: Cursor? = null
            try {
                if (key.isEmpty()) return@withContext defaultValue
                val uri = Uri.parse("content://com.gankao.dpcmanager.provider/cache")
                cursor = context.contentResolver.query(uri, arrayOf("key", "value"), "key = ?", arrayOf(key), null)
                if (cursor?.moveToNext() == true) {
                    return@withContext cursor.getString(cursor.getColumnIndex(key)) ?: defaultValue
                }
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

    /**
     * 获取用户 sn号
     */
    suspend fun getDeviceSn(context: Context): String? {
        return getString(context, "dpc_device_sn")
    }

    /**
     * 获取设备Id gk生成
     */
    suspend fun getDeviceId(context: Context): String? {
        return getString(context, "dpc_device_id")
    }

    /**
     * 获取区域Id
     */
    suspend fun getAreaId(context: Context): String? {
        return getString(context, "dpc_area_id")
    }

    /**
     * 获取公司Id
     */
    suspend fun getCompanyId(context: Context): String? {
        return getString(context, "dpc_company_id")
    }

    /**
     * 获取黑名单列表
     */
    suspend fun getBlackAppList(context: Context): String? {
        return getString(context, "dpc_black_app_list")
    }

    suspend fun getWhiteAppList(context: Context): String? {
        return getString(context, "dpc_white_app_list")
    }

    /**
     * "1", 非白即黑
     * "0", 非黑即白
     */
    suspend fun getEtherWhiteOrBlackStrategy(context: Context): String? {
        return getString(context, "dpc_app_either_white_or_black")
    }

    /**
     * 进入家长模式是否 禁用
     */
    suspend fun isEnterParentModeDisabled(context: Context): Boolean {
        return getString(context, "dpc_enter_parent_mode_disable", "0") == "1"
    }

    /**
     * 是否进入了家长模式
     */
    suspend fun isEnterParentMode(context: Context): Boolean {
        return getString(context, "dpc_enter_parent_mode", "0") == "1"
    }

    /**
     * 是否 锁屏
     * 1: true
     * 0: false
     */
    suspend fun isScreenLocked(context: Context): Boolean {
        return getString(context, "dpc_is_screen_locked", "0") == "1"
    }

    /**
     * 是否 锁屏
     * "1" 禁用锁屏
     * "0" 不禁用锁屏
     */
    suspend fun isGesturePasswordDisabled(context: Context): Boolean {
        return getString(context, "dpc_gesture_password_disabled", "0") == "1"
    }

    /**
     * 获取最终 黑名单列表
     */
    suspend fun getFinalBlackAppList(context: Context): String? {
        return getString(context, "dpc_final_black_app_list")
    }

    /**
     * 是否是 Harmony os
     */
    suspend fun isHarmonyOs(context: Context): Boolean {
        return getString(context, "dpc_is_harmony_os", "0") == "1"
    }

    /**
     * Harmony os Version
     */
    suspend fun getHarmonyOsVersion(context: Context): String? {
        return getString(context, "dpc_harmony_os_version")
    }

    /**
     * 获取 ota 版本
     */
    suspend fun getOtaVersion(context: Context): String? {
        return getString(context, "dpc_ota_version")
    }


    /**
     * isKeepAlive
     */
    suspend fun isKeepAlive(context: Context): Boolean {
        return getString(context, "is_keep_alive") == "1"
    }

}