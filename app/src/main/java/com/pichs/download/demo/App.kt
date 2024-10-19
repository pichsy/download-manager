package com.pichs.download.demo

import android.app.Application
import com.pichs.download.Downloader
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.cache.BaseMMKVHelper
import com.pichs.xbase.utils.UiKit

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        UiKit.init(this)
        Downloader.with().init(this)
        BaseMMKVHelper.init(this)
        LogUtils.setLogEnable(true)
        ToastUtils.init(this)
    }
}