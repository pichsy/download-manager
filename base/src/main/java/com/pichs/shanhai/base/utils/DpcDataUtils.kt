package com.pichs.shanhai.base.utils

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

object DpcDataUtils {

    @SuppressLint("Range")
    suspend fun getString(context: Context, key: String, defaultValue: String? = null): String? {
        return withContext(Dispatchers.IO) {
            var cursor: Cursor? = null
            try {
                if (key.isEmpty()) return@withContext defaultValue
                val uri = "content://com.gankaos.ai.dpc.provider/cache".toUri()
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

    @SuppressLint("Range")
    fun getStringASync(context: Context, key: String, defaultValue: String? = null): String? {
        return run {
            var cursor: Cursor? = null
            try {
                if (key.isEmpty()) return@run defaultValue
                val uri = "content://com.gankaos.ai.dpc.provider/cache".toUri()
                cursor = context.contentResolver.query(uri, arrayOf("key", "value"), "key = ?", arrayOf(key), null)
                if (cursor?.moveToNext() == true) {
                    return@run cursor.getString(cursor.getColumnIndex(key)) ?: defaultValue
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
     * 获取用户 sn号
     */
    fun getDeviceSnASync(context: Context): String {
        return getStringASync(context, "dpc_device_sn") ?: ""
    }


    /**
     * 获取 剪流的 token
     */
    suspend fun getJianLiuToken(context: Context): String? {
        return getString(context, "app_jian_liu_token")
    }


    /**
     * 获取 剪流的 token
     */
    suspend fun getJianLiuTokenExpireTime(context: Context): Long {
        return try {
            getString(context, "app_jian_liu_token_expire_timestamp")?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 获取 云销售的 token
     */
    suspend fun getSalesToken(context: Context): String? {
        return getString(context, "app_sales_app_token")
    }

    suspend fun getSalesTokenExpireTime(context: Context): Long {
        return try {
            getString(context, "app_sales_app_token_expire_timestamp")?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 获取 赶考系 token
     */
    suspend fun getGankaoToken(context: Context): String? {
        return getString(context, "app_gankao_app_token")
    }

    /**
     * 获取 赶考系 cookie
     */
    suspend fun getGankaoCookie(context: Context): String? {
        return getString(context, "app_gankao_app_cookies")
    }

    /**
     * 获取 赶考系 用户信息
     * data class LoginV3UserInfo(
     *     val id:Long?=0L,
     *     val user_id: Long? = 0L,
     *     val mobile: String? = null,
     *     val real_name: String? = null,
     *     val mystudent_name: String? = null,
     *     val nick_name: String? = null,
     *     val logo: String? = null,
     *     val user_type: String? = null,
     *     val grade_id: Int? = 0,
     *     val gradename: String? = null,
     *     val isTrueTeacher: Boolean? = false,
     *     val user_channel_type: String? = null,
     *     val partner_id: String? = null,
     *     val login_day: Int? = 0,
     *     val login_day_continuity: Int? = 0,
     *     val cookieStudent: String? = null,
     * ): Serializable{
     *     fun getUserId(): Long{
     *         if (user_id==null||user_id == 0L){
     *             return id ?: 0L
     *         }
     *         return user_id
     *     }
     * }
     */
    suspend fun getGankaoUserInfo(context: Context): String? {
        return getString(context, "app_gankao_app_user_info")
    }

    /**
     * 获取 赶考系 用户id
     * String:  user_id
     */
    suspend fun getGankaoUserId(context: Context): String? {
        return getString(context, "app_gankao_app_user_id")
    }

    /**
     * json字符串
     * data class V6BindInfoData(
     *     val isBind: Boolean = false,
     *     val bindInfo: V6BindInfoDetail? = null,
     * )
     *
     * data class V6BindInfoDetail(
     *     val enterpriseId: String? = null,
     *     val enterpriseName: String? = null,
     *     val userId: String? = null,
     *     val userName: String? = null
     * )
     */
    suspend fun getSalesBindInfo(context: Context): String? {
        return getString(context, "app_sales_bind_info")
    }

    /**
     * release / preview / test
     * 获取不到，自行处理默认环境
     */
    suspend fun getEnv(context: Context): String? {
        return getString(context, "gk_dpc_env")
    }

    /**
     * 是否 ： 是 "true" 。 否：null/empty/false。
     */
    suspend fun isAiPhone(context: Context): Boolean {
        return getString(context, "isAiPhone") == "true"
    }

    /**
     * 是否 ： 是 "true" 。 否：null/empty/false。
     */
    fun isAiPhoneASync(context: Context): Boolean {
        return getStringASync(context, "isAiPhone") == "true"
    }

    /**
     * 设备是否绑定 ： 是 "1" 。 否："0"。
     */
    fun isBindASync(context: Context): Boolean {
        return getStringASync(context, "dpc_bind_state") == "1"
    }

    /**
     * 设备是否绑定 ： 是 "1" 。 否："0"。
     */
    suspend fun isBind(context: Context): Boolean {
        return getString(context, "dpc_bind_state") == "1"
    }

    /**
     *  获取上传流量限制
     *  20, 50, 100, 200, 500, 1000
     *  单位 MB 数值：[20,50,100,200,500,1000]
     */
    suspend fun getUploadDataUseSmartLimit(context: Context): Int {
        return getString(context, "dpc_upload_data_use_smart_limit")?.toIntOrNull() ?: 0
    }

    /**
     *   获取上传 流量限制提醒类型
     *   0， 每次都提醒，1，不提醒，2，智能提醒
     */
    suspend fun getUploadDataUseTipsType(context: Context): Int {
        return getString(context, "dpc_upload_data_use_tips_type")?.toIntOrNull() ?: 0
    }

    /**
     *   获取上传 流量限制提醒类型
     *   0， 每次都提醒，1，不提醒，2，智能提醒
     */
    suspend fun isUploadWifiOnly(context: Context): Boolean {
        return getString(context, "dpc_wifi_upload_only_sync") == "1"
    }


}