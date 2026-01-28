package com.pichs.download.demo

import android.content.Context
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.xbase.utils.GsonUtils

/**
 * 读取并缓存 assets/recommend_apps.json，提供基于 packageName / 展示名 的查询。
 */
object CatalogRepository {

    @Volatile
    private var cache: AppListBean? = null

    private fun normalizeName(n: String): String = n.substringBeforeLast('.')
        .trim()
        .lowercase()

    fun getApps(context: Context): AppListBean? {
        val exist = cache
        if (exist != null) return exist
        synchronized(this) {
            val again = cache
            if (again != null) return again
            val json = runCatching { context.assets.open("recommend_apps.json").bufferedReader().use { it.readText() } }.getOrNull()
            LogUtils.d("数据列表读取：json=$json")
            val bean = json?.let {
                GsonUtils.fromJson(it, AppListBean::class.java)
            }
            LogUtils.d("数据列表读取：AppListBean=$bean")

            cache = bean
            return bean
        }
    }

    private var gameCache: AppListBean? = null

    fun getGames(context: Context): AppListBean? {
        val exist = gameCache
        if (exist != null) return exist
        synchronized(this) {
            val again = gameCache
            if (again != null) return again
            val json = runCatching { context.assets.open("recommend_games.json").bufferedReader().use { it.readText() } }.getOrNull()
            val bean = json?.let {
                GsonUtils.fromJson(it, AppListBean::class.java)
            }
            gameCache = bean
            return bean
        }
    }

    fun findByPackageName(context: Context, packageName: String): DownloadItem? =
        getApps(context)?.appList?.firstOrNull { it.packageName == packageName }
            ?: getGames(context)?.appList?.firstOrNull { it.packageName == packageName }

    fun findByDisplayName(context: Context, displayName: String): DownloadItem? =
        getApps(context)?.appList?.firstOrNull { normalizeName(it.name) == normalizeName(displayName) }
            ?: getGames(context)?.appList?.firstOrNull { normalizeName(it.name) == normalizeName(displayName) }

    fun resolveForTask(context: Context, taskFileName: String): DownloadItem? =
        findByDisplayName(context, taskFileName)

    fun getStoreVersionCode(context: Context, packageName: String): Long? =
        findByPackageName(context, packageName)?.versionCode

    fun getStoreVersionName(context: Context, packageName: String): String? =
        findByPackageName(context, packageName)?.versionName
}
