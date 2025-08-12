package com.pichs.download.demo

import android.content.Context
import com.pichs.xbase.utils.GsonUtils

/**
 * 读取并缓存 assets/app_list.json，提供基于 packageName / 展示名 的查询。
 */
object CatalogRepository {

    @Volatile
    private var cache: AppListBean? = null

    private fun normalizeName(n: String): String = n.substringBeforeLast('.')
        .trim()
        .lowercase()

    fun invalidate() { cache = null }

    fun get(context: Context): AppListBean? {
        val exist = cache
        if (exist != null) return exist
        synchronized(this) {
            val again = cache
            if (again != null) return again
            val json = runCatching { context.assets.open("app_list.json").bufferedReader().use { it.readText() } }.getOrNull()
            val bean = json?.let { GsonUtils.fromJson<AppListBean>(it, AppListBean::class.java) }
            cache = bean
            return bean
        }
    }

    fun findByPackageName(context: Context, packageName: String): DownloadItem? =
        get(context)?.appList?.firstOrNull { it.packageName == packageName }

    fun findByDisplayName(context: Context, displayName: String): DownloadItem? =
        get(context)?.appList?.firstOrNull { normalizeName(it.name) == normalizeName(displayName) }

    fun resolveForTask(context: Context, taskFileName: String): DownloadItem? =
        findByDisplayName(context, taskFileName)

    fun getStoreVersionCode(context: Context, packageName: String): Long? =
        findByPackageName(context, packageName)?.versionCode

    fun getStoreVersionName(context: Context, packageName: String): String? =
        findByPackageName(context, packageName)?.versionName
}
