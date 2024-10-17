package com.pichs.shanhai.base.api

import com.pichs.shanhai.base.http.BaseApi
import com.pichs.shanhai.base.http.interceptor.TokenInterceptor
import okhttp3.Interceptor

object ShanHaiApi : BaseApi<ShanHaiApiService>() {

    private const val BASE_URL_TEST = "http://172.16.100.17:8080/"

    override fun getReleaseBaseUrl(): String {
        return BASE_URL_TEST
    }

    override fun getPreviewBaseUrl(): String {
        return BASE_URL_TEST
    }

    override fun getTestBaseUrl(): String {
        return BASE_URL_TEST
    }

    override fun customInterceptor(): Interceptor? {
        return TokenInterceptor.instance
    }


    // 登录
    const val LOGIN = "login"

    // 销户
    const val DESTROY_ACCOUNT = "destroyAccount"

    // 获取用户信息
    const val USER_INFO = "userInfo"

    // 修改用户信息
    const val UPDATE_USERINFO = "updateUserInfo"

    // 修改手机号码
    const val UPDATE_PHONE = "updatePhone"

}