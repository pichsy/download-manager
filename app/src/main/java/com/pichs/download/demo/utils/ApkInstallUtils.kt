package com.pichs.download.demo.utils

import android.content.Context
import android.content.pm.PackageInfo
import com.pichs.xbase.utils.SysOsUtils

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
}