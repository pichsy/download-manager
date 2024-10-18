package com.pichs.download.utils

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object ApkUtils {

    /**
     * 校验 APK 文件是否完整有效
     * @param context 上下文
     * @param apkFile APK 文件
     * @param expectedSize 预期的文件大小（字节），如果不需要检查大小可以传入 -1
     * @return 是否是有效的 APK 文件
     */
    suspend fun isApkValid(context: Context, apkFile: File, expectedSize: Long = -1): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 检查文件是否存在
                if (!apkFile.exists() || !apkFile.isFile) {
                    return@withContext false
                }

                // 检查文件大小（如果提供了预期大小）
                if (expectedSize > 0 && apkFile.length() != expectedSize) {
                    return@withContext false
                }

                // 尝试打开 APK 作为 ZIP 文件
                ZipFile(apkFile).use { zip ->
                    // 检查 ZIP 文件的完整性
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        zip.getInputStream(entry).use { it.readBytes() }
                    }
                }

                // 使用 PackageManager 解析 APK
                val packageInfo = context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.GET_SIGNATURES
                )

                // 如果 packageInfo 为 null，说明 APK 无法被解析
                packageInfo != null
            } catch (e: Exception) {
                // 如果在任何步骤中出现异常，认为 APK 无效
                false
            }
        }
    }
}