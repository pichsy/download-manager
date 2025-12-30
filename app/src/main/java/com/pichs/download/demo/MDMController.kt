package com.pichs.download.demo

import android.content.Intent
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.xbase.kotlinext.sendBroadcastSafely
import com.pichs.xbase.utils.UiKit

object MDMController {

    /**
     * 静默安装
     * intent.putExtra("install_path", path); path 为需要安装的应用所在储存路径
     */
    const val INSTALL = "com.gankao.dpc.request.INSTALL"

    /**
     * 静默安装 APK
     */
    fun installSilent(apkPath: String) {
        try {
            // 假装进行静默安装 且正在安装，通过监听安装广播来处理安装后的状态更新
            LogUtils.d(" MDMController", "installSilent: $apkPath")
            UiKit.sendBroadcastSafely(Intent(INSTALL).apply {
                putExtra("install_path", apkPath)
                setPackage("com.gankaos.ai.dpc")
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}