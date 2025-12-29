package com.pichs.download.demo

import com.pichs.shanhai.base.utils.LogUtils

object MDMController {


    /**
     * 静默安装 APK
     */
    fun installSilent(apkPath: String) {
        try {
            // 假装进行静默安装 且正在安装，通过监听安装广播来处理安装后的状态更新
            LogUtils.d(" MDMController", "installSilent: $apkPath")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}