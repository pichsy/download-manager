package com.pichs.shanhai.base.http

import com.google.gson.Gson
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.xbase.map.doubleKeyMapOf
import com.pichs.xbase.xlog.XLog
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.lang.Exception
import java.util.concurrent.TimeUnit

class RetrofitFactory {

    private val baseUrlMap = mutableMapOf<String, Retrofit>()
    private val sMap = doubleKeyMapOf<String, String, Any>()
    private val TIMEOUT = 50 * 1000L

    fun <T : Any> getApiService(baseUrl: String, serviceClass: Class<T>, customInterceptor: Interceptor? = null): T {
        if (sMap.get(baseUrl, serviceClass.name) != null) {
            try {
                return sMap.get(baseUrl, serviceClass.name) as T
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        var retrofit = baseUrlMap[baseUrl]
        if (retrofit == null) {
            retrofit = createRetrofit(baseUrl, customInterceptor)
            baseUrlMap[baseUrl] = retrofit
        }
        val create = retrofit.create(serviceClass)
        sMap.put(baseUrl, serviceClass.name, create)
        return create
    }

    private fun createRetrofit(baseUrl: String, customInterceptor: Interceptor? = null): Retrofit {
        return Retrofit.Builder()
            .addConverterFactory(GsonConverterFactory.create(Gson()))
//            .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
            .baseUrl(baseUrl)
            .client(createOkHttpClient(customInterceptor))
            .build()
    }

    private fun createOkHttpClient(customInterceptor: Interceptor?): OkHttpClient {
        // newBuilder() 创建 OkHttpClient, 内部复用 dispatcher（多个对象使用同一个线程池）
        val okHttpClient: OkHttpClient = OkHttpClient().newBuilder().apply {
            retryOnConnectionFailure(true)
            connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            // 添加自定定义的拦截器，公共数据在此处添加
            customInterceptor?.let { itc ->
                addInterceptor(itc)
            }
            addInterceptor(
                HttpLoggingInterceptor { message ->
                    LogUtils.d("http=>", " $message")
                }.setLevel(HttpLoggingInterceptor.Level.BODY)
            )
        }.build()
        //perHost最大链接数(默认是5)
        okHttpClient.dispatcher.maxRequestsPerHost = 5
        return okHttpClient
    }

}
