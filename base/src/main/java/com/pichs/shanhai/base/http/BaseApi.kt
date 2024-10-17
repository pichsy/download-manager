package com.pichs.shanhai.base.http

import com.pichs.shanhai.base.http.env.EnvUtils
import com.pichs.xbase.kotlinext.isTRUE
import okhttp3.Interceptor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.ParameterizedType

/**
 * 基类
 * 使用时可以实现此类，自己创建方法实现自己的 Api 对象
 * 封装自己的请求。
 */
abstract class BaseApi<ApiSerVice : Any> {

    /**
     * 对外开放的api方法。
     */
    private fun initApi(): ApiSerVice {
        return RetrofitManager.get().getApiService(getBaseUrlByEnv(), getFirstGenericClazz(), customInterceptor())
    }

    fun getApi(): ApiSerVice {
        return initApi()
    }

    /**
     * 不到万不得已尽量不要用。
     * 用的时候也要自己注意环境地址
     */
    fun getApi(baseUrl: String): ApiSerVice {
        return RetrofitManager.get().getApiService(baseUrl, getFirstGenericClazz(), customInterceptor())
    }

    /**
     * 自定义拦截器
     */
    open fun customInterceptor(): Interceptor? {
        return null
    }

    /**
     * 获取环境变量，动态获取 base_url
     */
    open fun getBaseUrlByEnv(): String {
        try {
            val current = EnvUtils.getEnv()
            if ("preview" == current) {
                return getPreviewBaseUrl()
            } else if ("test" == current) {
                return getTestBaseUrl()
            }
        } catch (e: Exception) {
            return getReleaseBaseUrl()
        }
        return getReleaseBaseUrl()
    }

    /**
     * 指定当前api类的 BaseUrl
     */
    abstract fun getReleaseBaseUrl(): String

    abstract fun getPreviewBaseUrl(): String

    abstract fun getTestBaseUrl(): String

    @Suppress("UNCHECKED_CAST")
    private fun getFirstGenericClazz(): Class<ApiSerVice> {
        (this.javaClass.genericSuperclass is ParameterizedType).isTRUE {
            (this.javaClass.genericSuperclass as ParameterizedType).actualTypeArguments.forEach {
                try {
                    return@getFirstGenericClazz (it as Class<ApiSerVice>)
                } catch (_: NoSuchMethodException) {
                } catch (_: ClassCastException) {
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        }
        throw NullPointerException("Cannot find genericSuperclass, please check your code is not wrong")
    }
}


