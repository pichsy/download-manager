package com.pichs.download.demo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.pichs.download.demo.install.InstallManager
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.xbase.utils.UiKit

class InstallBroadcastReceiver : BroadcastReceiver() {

    fun register() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED)
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        intentFilter.addDataScheme("package")
        try {
            ContextCompat.registerReceiver(UiKit.getApplication(), this, intentFilter, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun unregister() {
        try {
            UiKit.getApplication().unregisterReceiver(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    // 安装事件缓存。
    override fun onReceive(context: Context?, intent: Intent?) {
        intent?.let {
            when (it.action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    val isRemoving = intent.getBooleanExtra("android.intent.extra.REMOVED_FOR_ALL_USERS", false)
                    LogUtils.d("应用安装888：安装事件：add: isReplacing:${isReplacing},isRemoving:${isRemoving}")
                    intent.dataString?.replace("package:", "")?.let { pkg ->

                        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        // 应用新装
                        if (!isReplacing) {
                            LogUtils.d("应用安装888 ：安装事件：add===pkg=${pkg}")
                            // 通知 InstallManager 安装成功
                            InstallManager.onInstallSuccess(pkg)
                        } else {
                            LogUtils.d("应用安装888 ：安装事件：add===pkg=${pkg}，正在被替换安装，不处理")
                        }
                    }
                }

                Intent.ACTION_PACKAGE_REPLACED -> {
                    LogUtils.d("应用安装888 ：替换安装事件：replace")
                    intent.dataString?.replace("package:", "")?.let { pkg ->
                        LogUtils.d("应用安装888 ：替换安装事件：replace===pkg=${pkg}")
                        // 通知 InstallManager 安装成功（替换安装也算成功）
                        InstallManager.onInstallSuccess(pkg)
                    }
                }

                Intent.ACTION_PACKAGE_REMOVED -> {
                    val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    val isRemoving = intent.getBooleanExtra("android.intent.extra.REMOVED_FOR_ALL_USERS", false)
                    LogUtils.d("应用安装888：卸载事件：remove: isReplacing:${isReplacing},isRemoving:${isRemoving}")
                    if (!isReplacing) {
                        intent.dataString?.replace("package:", "")?.let { pkg ->
                            LogUtils.d("应用安装888 ：卸载事件：remove===pkg=${pkg}")
                        }
                    } else {
                        LogUtils.d("应用安装888 ：卸载事件：remove===正在被替换安装，不处理")
                    }
                }

                else -> {
                    LogUtils.d("应用安装888 ：未知事件")
                }
            }
        }
    }
}