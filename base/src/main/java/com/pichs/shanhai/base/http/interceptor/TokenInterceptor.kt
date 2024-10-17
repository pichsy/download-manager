package com.pichs.shanhai.base.http.interceptor

import android.os.Build
import com.pichs.shanhai.base.api.entity.UserInfo
import com.pichs.shanhai.base.user.UserInfoUtils
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
        builder.addHeader("appVersionName", SysOsUtils.getVersionName(UiKit.getApplication()))
        builder.addHeader("appVersionCode", SysOsUtils.getVersionCode(UiKit.getApplication()).toString())
        builder.addHeader("model", Build.MODEL)
        builder.addHeader("brand", Build.BRAND)
        builder.addHeader("uid", UserInfoUtils.getUid())
        builder.addHeader("token", UserInfoUtils.getToken())
        val rst: Request = builder.build()
        return chain.proceed(rst)
    }
}