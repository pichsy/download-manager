package com.pichs.download.demo

import android.content.Context
import android.content.Intent
import com.pichs.download.model.DownloadTask
import java.io.File

object AppUtils {

    private fun normalizeName(n: String): String = n.substringBeforeLast('.')
        .trim()
        .lowercase()

    // 不再通过 URL 获取版本号或包名，统一走本地 catalog（app_list.json）
    fun getCatalog(context: Context): AppListBean? = CatalogRepository.get(context)

    fun getStoreVersionCode(context: Context, packageName: String): Long? =
        CatalogRepository.getStoreVersionCode(context, packageName)

    fun getStoreVersionName(context: Context, packageName: String): String? =
        CatalogRepository.getStoreVersionName(context, packageName)

    fun getCatalogItemByName(context: Context, displayName: String): DownloadItem? =
        CatalogRepository.findByDisplayName(context, displayName)

    fun getPackageNameByName(context: Context, displayName: String): String? =
        getCatalogItemByName(context, displayName)?.packageName

    fun getPackageNameForTask(context: Context, task: DownloadTask): String? =
        CatalogRepository.resolveForTask(context, task.fileName)?.packageName

    fun getStoreVersionCodeForTask(context: Context, task: DownloadTask): Long? =
        CatalogRepository.resolveForTask(context, task.fileName)?.versionCode

    fun getInstalledVersionCode(context: Context, packageName: String): Long? = runCatching {
        val pm = context.packageManager
        val pi = pm.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }.getOrNull()

    fun isInstalledAndUpToDate(context: Context, packageName: String, storeVC: Long?): Boolean {
        if (storeVC == null) return false
        val installed = getInstalledVersionCode(context, packageName) ?: return false
        return installed >= storeVC
    }

    fun isInstalledAndUpToDate(context: Context, item: DownloadItem): Boolean {
        val pkg = item.packageName.ifBlank { getPackageNameByName(context, item.name) ?: return false }
        val storeVC = item.versionCode ?: getStoreVersionCode(context, pkg)
        return isInstalledAndUpToDate(context, pkg, storeVC)
    }

    fun isInstalledAndUpToDate(context: Context, task: DownloadTask): Boolean {
        val pkg = getPackageNameForTask(context, task) ?: return false
        val storeVC = getStoreVersionCodeForTask(context, task)
        return isInstalledAndUpToDate(context, pkg, storeVC)
    }

    fun openApp(context: Context, packageName: String): Boolean = runCatching {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    enum class FileHealth { OK, MISSING, DAMAGED }

    fun checkFileHealth(task: DownloadTask): FileHealth {
        val f = File(task.filePath, task.fileName)
        if (!f.exists()) return FileHealth.MISSING
        return if (task.totalSize > 0 && f.length() != task.totalSize) FileHealth.DAMAGED else FileHealth.OK
    }
}
