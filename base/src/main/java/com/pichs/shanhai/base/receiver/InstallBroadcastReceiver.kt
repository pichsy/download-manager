package com.pichs.shanhai.base.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.pichs.xbase.utils.UiKit
import com.pichs.xbase.xlog.XLog

class InstallBroadcastReceiver : BroadcastReceiver() {

    fun register(lifecycleOwner: LifecycleOwner) {
        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED)
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        intentFilter.addDataScheme("package")
        try {
            ContextCompat.registerReceiver(UiKit.getApplication(), this, intentFilter, ContextCompat.RECEIVER_EXPORTED)
            lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    try {
                        UiKit.getApplication().unregisterReceiver(this@InstallBroadcastReceiver)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 安装事件缓存。
    override fun onReceive(context: Context?, intent: Intent?) {
        intent?.let {
            val isRe = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            val pname = intent.getBooleanExtra(Intent.EXTRA_PACKAGE_NAME, false)
            XLog.d("应用安装888--InstallBroadcastReceiver000: action:${it.action},isReplacing:${isRe},packageName:${pname}")
            when (it.action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    intent.dataString?.replace("package:", "")?.let { pkg ->
                        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        XLog.d("应用安装888：add: isReplacing:${isReplacing}")
                        // 应用新装
                        if (!isReplacing) {
                            XLog.d("应用安装888：新增应用：pkg:${pkg}")
                        }
                    }
                }

                Intent.ACTION_PACKAGE_REPLACED -> {
                    intent.dataString?.replace("package:", "")?.let { pkg ->
                        XLog.d("应用安装888： 覆盖安装：pkg:${pkg}")
                    }
                }

                Intent.ACTION_PACKAGE_REMOVED -> {
                    val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    val isRemoving = intent.getBooleanExtra("android.intent.extra.REMOVED_FOR_ALL_USERS", false)
                    XLog.d("应用安装888：remove: isReplacing:${isReplacing}")
                    XLog.d("应用安装888：remove: isRemoving:${isRemoving}")

                    if (!isReplacing) {
                        intent.dataString?.replace("package:", "")?.let { pkg ->
                            XLog.d("应用安装888： 卸载了：pkg:${pkg}")
                            // 卸载。
                        }
                    } else {
                        // nothing to do
                    }
                }

                else -> {
                    // nothing to do
                }
            }
        }
    }
}