package com.pichs.shanhai.base.http

import okhttp3.Interceptor

class RetrofitManager private constructor() {

    private var mRetrofitFactory = RetrofitFactory()

    companion object {

        private val instance: RetrofitManager by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) { RetrofitManager() }

        fun get(): RetrofitManager {
            return instance
        }

    }

    fun <T : Any> getApiService(baseUrl: String, serviceClass: Class<T>, customInterceptor: Interceptor? = null): T {
        return mRetrofitFactory.getApiService(baseUrl, serviceClass, customInterceptor)
    }

}
