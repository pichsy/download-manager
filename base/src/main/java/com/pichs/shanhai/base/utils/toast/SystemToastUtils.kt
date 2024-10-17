package com.pichs.shanhai.base.utils.toast

import com.hjq.toast.ToastParams
import com.hjq.toast.Toaster


/**
 * 需要悬浮窗权限
 */
object SystemToastUtils {

    fun show(text: String, duration: Int = 5000, icon: Int? = null) {
        val params = ToastParams()
        params.text = text
        params.duration = duration
        params.crossPageShow = true
        params.style = ShanhaiToastStyle()
        Toaster.show(params)
    }


}