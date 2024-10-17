package com.pichs.shanhai.base.utils.toast

import android.app.Application
import com.hjq.toast.ToastStrategy
import com.hjq.toast.Toaster

/**
 * 自定义toast样式
 * 请修改 [ShanhaiToastStyle]
 */
object ToastUtils {
    fun init(application: Application) {
        Toaster.init(
            application,
            ToastStrategy(ToastStrategy.SHOW_STRATEGY_TYPE_IMMEDIATELY),
            ShanhaiToastStyle()
        )
    }

    fun show(str: String?) {
        toastShort(str)
    }

    fun toast(str: String?) {
        toastShort(str)
    }

    fun toastShort(str: String?) {
        Toaster.showShort(str ?: "null")
    }

    fun showLong(str: String?) {
        toastLong(str)
    }

    fun toastLong(str: String?) {
        Toaster.showLong(str ?: "null")
    }

    /**
     * 延迟展示吐司
     */
    fun toastDelay(str: String?, delayMills: Long) {
        Toaster.delayedShow(str ?: "null", delayMills)
    }

    /**
     * 仅debug模式展示的吐司
     */
    fun debugToast(str: String?) {
        Toaster.debugShow(str)
    }

}