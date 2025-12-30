package com.pichs.download.demo.utils

import android.content.Context
import android.content.pm.PackageInfo
import com.pichs.xbase.utils.SysOsUtils
import java.io.File
import android.content.Intent
import androidx.core.content.FileProvider

object ApkInstallUtils {

    fun checkAppInstalled(context: Context, packageName: String?): Boolean {
        var packageInfo: PackageInfo? = null
        try {
            if (packageName.isNullOrEmpty()) return false
            packageInfo = SysOsUtils.getPackageManager(context).getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            // nothing to do
        }
        return packageInfo != null
    }

    /**
     * 安装 APK 文件
     */
    fun installApk(context: Context, file: File) {
        if (!file.exists()) {
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
    }

}