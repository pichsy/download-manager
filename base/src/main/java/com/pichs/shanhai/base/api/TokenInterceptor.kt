package com.pichs.shanhai.base.api

import android.os.Build
import com.pichs.xbase.utils.SysOsUtils
import com.pichs.xbase.utils.UiKit
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class TokenInterceptor private constructor() : Interceptor {

    companion object {
        val instance by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TokenInterceptor() }
    }

    /**
     * 拦截并添加公共请求头
     */
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
//        builder.addHeader("app-version", SysOsUtils.getVersionName(UiKit.getApplication()))
//        builder.addHeader("dpc_version_code", SysOsUtils.getVersionCode(UiKit.getApplication()).toString())
//        builder.addHeader("model", Build.MODEL)
//        builder.addHeader("brand", Build.BRAND)
//        builder.addHeader("system-version", "Android " + Build.VERSION.RELEASE + "(" + Build.VERSION.SDK_INT + ")")
        builder.addHeader("app-version", SysOsUtils.getVersionName(UiKit.getApplication()))
        builder.addHeader("dpc_version_code", SysOsUtils.getVersionCode(UiKit.getApplication()).toString())
        builder.addHeader("model", Build.MODEL)
        builder.addHeader("brand", Build.BRAND)
        builder.addHeader("system-version", "Android " + Build.VERSION.RELEASE + "(" + Build.VERSION.SDK_INT + ")")
//        builder.addHeader("uid", UserInfoUtils.getUid())
//        builder.addHeader("token", UserInfoUtils.getToken())
        val rst: Request = builder.build()
        return chain.proceed(rst)
    }
}