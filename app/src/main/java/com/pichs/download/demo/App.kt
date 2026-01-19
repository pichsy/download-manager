package com.pichs.download.demo

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.pichs.download.core.DownloadManager
import com.pichs.download.utils.DownloadLog
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.cache.BaseMMKVHelper
import com.pichs.xbase.utils.UiKit
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        UiKit.init(this)
        BaseMMKVHelper.init(this)
        // 初始化阈值管理器
        CellularThresholdManager.init(this)
        LogUtils.setLogEnable(true)
        ToastUtils.init(this)
        DownloadManager.init(this)
        // 显式设置最大并发下载=3（默认即为3，这里加一行便于配置清晰）
        DownloadManager.setMaxConcurrent(1)
        DownloadManager.config {
            defaultDownloadDirPath = "${this@App.getExternalFilesDir(null)?.absolutePath ?: cacheDir.absolutePath}/DownloadApks"
        }
        
        // ✅ 配置 Retention Policy（可选）
        // 设置保护期为 24 小时（默认48小时），刚下载完成的任务在24小时内不会被清理
        DownloadManager.setRetentionConfig(
            com.pichs.download.config.RetentionConfig(
                protectionPeriodHours = 24,    // 保护期：24小时
                keepCompletedDays = 30,       // 保留已完成任务30天
                keepLatestCompleted = 100     // 最多保留100个已完成任务
            )
        )
        
        // 应用启动时异步清理无效任务
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            val cleanedCount = DownloadManager.validateAndCleanTasks()
            if (cleanedCount > 0) {
                DownloadLog.d("App", "应用启动清理了 $cleanedCount 个无效任务")
            }
            
            // ✅ 执行 Retention Policy 清理过期任务（排除48小时内的新任务）
            DownloadManager.executeRetentionPolicy()
            DownloadLog.d("App", "应用启动时执行了 Retention Policy 清理")
        }
    }
}