package com.pichs.download.demo

import android.app.Application
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.cache.BaseMMKVHelper
import com.pichs.xbase.utils.UiKit

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        UiKit.init(this)
        BaseMMKVHelper.init(this)
        LogUtils.setLogEnable(true)
        ToastUtils.init(this)
        com.pichs.download.core.DownloadManager.init(this)
        // 显式设置最大并发下载=3（默认即为3，这里加一行便于配置清晰）
        com.pichs.download.core.DownloadManager.setMaxConcurrent(3)
    }
}