package com.pichs.shanhai.base.route

import com.pichs.xbase.xlog.XLog
import com.therouter.router.Navigator
import com.therouter.router.defaultNavigationCallback
import com.therouter.router.interceptor.NavigationCallback

/**
 * 路由帮助类
 */
object RouterHelper {

    fun initRouter() {
        defaultNavigationCallback(object : NavigationCallback() {
            override fun onLost(navigator: Navigator, requestCode: Int) {
                super.onLost(navigator, requestCode)
                invokeRouterOnLost(navigator)
                XLog.i("TheRouter", "onLost navigator.path = ${navigator.url}  ${navigator.simpleUrl} ${navigator}")
            }
        })
    }

    private fun invokeRouterOnLost(navigator: Navigator) {
        when {
            navigator.url?.startsWith("http") == true -> {

            }

            navigator.url?.startsWith("scheme") == true -> {

            }
        }
    }
}