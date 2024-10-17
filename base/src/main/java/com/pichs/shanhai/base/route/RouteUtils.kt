package com.pichs.shanhai.base.route

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.therouter.TheRouter
import com.therouter.router.Navigator
import com.therouter.router.interceptor.NavigationCallback

object RouteUtils {

    fun jump(path: String, context: Context?) {
        TheRouter
            .build(path)
            .navigation(context, object : NavigationCallback() {
                override fun onLost(navigator: Navigator, requestCode: Int) {
                    ToastUtils.show("未找到页面")
                }
            })
    }

    fun build(path: String): Navigator {
        return TheRouter.build(path)
    }

    fun inject(any: Any) {
        TheRouter.inject(any)
    }

    fun init(context: Context?) {
        TheRouter.init(context)
    }

}