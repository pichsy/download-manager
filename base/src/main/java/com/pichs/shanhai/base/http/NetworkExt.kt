package com.pichs.shanhai.base.http

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pichs.shanhai.base.http.result.HttpDataResult
import com.pichs.shanhai.base.http.result.HttpException
import com.pichs.shanhai.base.http.result.HttpResultInterface
import com.pichs.shanhai.base.http.result.HttpSimpleResult
import com.pichs.shanhai.base.user.UserInfoUtils
import com.pichs.xbase.xlog.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException

private const val TAG = "NetworkExt"


/**
 * 是否需要登录 全局配置
 * 也可以在请求的时候单独配置 [HttpConfig.isNeedLogin]
 * 如果需要默认登录，需要配置登陆接口[invokeLoginRequest]
 */
private const val isNeedLoginGlobal = false

/**
 * 请求配置类 仅当前本次请求
 * @property isNeedUserRetry Boolean 是否需要用户手动重试重试 [invokeRetryRequest]
 *           需要实现重试弹窗逻辑  默认不需要
 * @property timeout Long 超时时间
 * @property retryCount Int 接口重试次数, 实际调用的接口发起请求的次数
 * @property isNeedLogin Boolean 是否需要登录，如果需要登录，需要配置登陆接口[invokeLoginRequest] 默认全局配置
 * @constructor
 */
data class HttpConfig(
    var isNeedUserRetry: Boolean = false,
    var timeout: Long = 10000L,
    var retryCount: Int = 3,
    var isNeedLogin: Boolean = isNeedLoginGlobal
)


suspend fun <T> requestResult(httpConfig: (HttpConfig) -> Unit = {}, request: suspend () -> HttpResultInterface<T>): Result<T> {
    val config = HttpConfig()
    httpConfig.invoke(config)
    var result = Result.failure<T>(Exception("未知异常"))
    runCatching {
        when (val r = invokeHttpResultInterface(request, {}, {}, config).getOrThrow()) {
            is HttpSimpleResult<T> -> {
                r.result
            }

            is HttpDataResult<T> -> {
                r.data
            }

            else -> {
                throw HttpException(404, "类型转换异常")
            }
        }
    }.onSuccess {
        result = Result.success(it)
    }
        .onFailure {
            result = Result.failure(it.toHttpException())
        }
    return result
}

suspend fun <T> requestResultBase(
    httpConfig: (HttpConfig) -> Unit = {},
    request: suspend () -> HttpResultInterface<T>
): Result<HttpResultInterface<T>> {
    val config = HttpConfig()
    httpConfig.invoke(config)
    var result = Result.failure<HttpResultInterface<T>>(Exception("未知异常"))
    runCatching {
        invokeHttpResultInterface(request, {}, {}, config).getOrThrow()
    }.onSuccess {
        result = Result.success(it)
    }
        .onFailure {
            result = Result.failure(it)
        }
    return result
}

suspend fun <T> request(httpConfig: (HttpConfig) -> Unit = {}, request: suspend () -> HttpResultInterface<T>): T? {
    val config = HttpConfig()
    httpConfig.invoke(config)
    return when (val result = invokeHttpResultInterface(request, {}, {}, config).getOrNull()) {
        is HttpSimpleResult<T> -> {
            result.result
        }

        is HttpDataResult<T> -> {
            result.data
        }

        else -> {
            null
        }
    }
}

fun <T> CoroutineScope.request(
    request: suspend () -> HttpResultInterface<T>,
    success: suspend (T?) -> Unit = {},
    failure: suspend (HttpException) -> Unit = {},
    httpConfig: (HttpConfig) -> Unit = {}
): Job {
    val config = HttpConfig()
    httpConfig.invoke(config)
    return launch(Dispatchers.IO) { invokeHttpResultInterface(request, success, failure, config) }
}


/**
 * gk请求
 */
fun <T> ViewModel.request(
    request: suspend () -> HttpResultInterface<T>,
    success: suspend (T?) -> Unit = {},
    failure: suspend (HttpException) -> Unit = {},
    httpConfig: (HttpConfig) -> Unit = {}
): Job {
    val config = HttpConfig()
    httpConfig.invoke(config)
    return viewModelScope.launch(Dispatchers.IO) {
        invokeHttpResultInterface(request, success, failure, config)
    }
}


/**
 * 网络请求以及返回结果处理
 * @receiver CoroutineScope
 * @param block SuspendFunction0<HttpResultInterface<T>>
 * @param success SuspendFunction1<T, Unit>
 * @param failure SuspendFunction1<HttpException, Unit>
 * @param httpConfig HttpConfig 请求配置
 * @return Job
 */
private suspend fun <T> invokeHttpResultInterface(
    block: suspend () -> HttpResultInterface<T>,
    success: suspend (T) -> Unit,
    failure: suspend (HttpException) -> Unit = {},
    httpConfig: HttpConfig
): Result<HttpResultInterface<T>> {
    return withContext(Dispatchers.IO) {
        runCatching {
            val result = invokeRealRequest(block, httpConfig)
            result?.getOrThrow() ?: throw Exception("未知异常")
        }.onSuccess {
            invokeHttpSuccessOrFailure(it, success, failure)
        }.onFailure {
            XLog.i("invokeHttpResultInterface  failure : $it")
            failure(it.toHttpException())
        }
    }
}

private suspend fun <T> invokeHttpSuccessOrFailure(
    result: HttpResultInterface<T>,
    success: suspend (T) -> Unit,
    failure: suspend (HttpException) -> Unit
): Result<T> {
    return result.let {
        when (it) {
            is HttpSimpleResult<T> -> {
                if (it.err == null) {
                    success(it.result)
                    Result.success(it.result)
                } else {
                    XLog.i("invokeHttpSuccessOrFailure HttpSimpleResult:  ${TAG} failure : $it")
                    val exception = HttpException(it.err?.code, it.err?.message)
                    failure(exception)
                    Result.failure(exception)
                }
            }

            is HttpDataResult<T> -> {
                if (it.status == 200) {
                    success(it.data)
                    Result.success(it.data)
                } else {
                    XLog.i("invokeHttpSuccessOrFailure HttpDataResult:  ${TAG} failure : $it")
                    val exception = HttpException(it.status, it.msg)
                    failure(exception)
                    Result.failure(exception)
                }
            }

            else -> {
                XLog.i("invokeHttpSuccessOrFailure HttpResultInterface:  ${TAG} failure : $it")
                val exception = HttpException(-1, "未知异常")
                failure(exception)
                Result.failure(exception)
            }
        }
    }
}

/**
 * 真正的网络请求逻辑，封装了登陆接口，重试机制等
 * @param block SuspendFunction0<T>
 * @param httpConfig HttpConfig 请求配置
 * @return Result<T>?
 */
private suspend fun <T> invokeRealRequest(block: suspend () -> T, httpConfig: HttpConfig): Result<T>? {
    var result: Result<T>? = null
    var isRetryCount = 0
    var isCheckExpire = true // 过期重试判断只执行一次
    var isResetRetryCount = false // 重置重试次数, 如果登陆接口多次重试失败，需要重置重试次数，保证当前接口的重试次数
    while (result?.isFailure != false && isRetryCount < httpConfig.retryCount) {
        isRetryCount++
        if (httpConfig.isNeedLogin) {
            val resultLogin = invokeDirectOrRetryRequest(block = ::invokeLoginRequest, httpConfig)
            if (!UserInfoUtils.isLogin()) {
                if (resultLogin?.getOrNull()?.isFailure == true) {
                    resultLogin?.getOrNull()?.exceptionOrNull()?.let {
                        result = Result.failure(Exception("第${isRetryCount}次尝试登录失败: msg:${it.message}"))
                        XLog.i("invokeRealRequest : $result")
                    }
                } else {
                    result = Result.failure(Exception("登录接口成功调用，但是isLogin=false"))
                }
                isResetRetryCount = true
                continue
            }
        }
        if (isResetRetryCount) {
            isResetRetryCount = false
            isRetryCount = 1
        }

        result = invokeDirectOrRetryRequest(block, httpConfig)

        //TODO
        // 判断token是否过期逻辑
        // 1.如果是token过期，重新登录
        if (isCheckExpire && checkTokenExpire(result) && httpConfig.isNeedLogin) {
            isCheckExpire = false
            isRetryCount = 0
            result = null
        }
    }
    return result
}


/**
 * 请求处理的方式，
 * 是否需要弱网重试弹窗等，不需要的话直接请求
 * @param block SuspendFunction0<T>
 * @param httpConfig: HttpConfig 请求配置
 * @return Result<T>?
 */
private suspend fun <T> invokeDirectOrRetryRequest(block: suspend () -> T, httpConfig: HttpConfig): Result<T>? {
    return when (httpConfig.isNeedUserRetry) {
        true -> invokeRetryRequest(block, httpConfig)
        false -> {
            runCatching { withTimeout(httpConfig.timeout) { block() } }
        }
    }
}


/**
 * 带有网络弹窗重试逻辑等的请求
 * @param block SuspendFunction0<T>
 * @param timeout Long
 * @return Result<T>?
 */
private suspend fun <T> invokeRetryRequest(block: suspend () -> T, httpConfig: HttpConfig): Result<T>? {
    var result: Result<T>? = null
    while (result == null) {
        result = runCatching {
            withTimeout(httpConfig.timeout) {
                block()
            }
        }
        XLog.i("withContext result = $result    ${result.exceptionOrNull()}")
        if (result.isFailure && result.exceptionOrNull()?.isNetworkException() == true && httpConfig.isNeedUserRetry) {
            val isAgain = withContext(Dispatchers.Main) {
                invokeShowNetPopup()
            }
            delay(500)
            if (isAgain) {
                result = null
            }
        }
    }
    return result
}


/**
 * TODO 判断token是否过期
 * @param result Result<H>?
 * @return Boolean
 */
private fun <T> checkTokenExpire(result: Result<T>?): Boolean {
    var isExpire = false
    result?.getOrNull()?.let {
        if (it is HttpResultInterface<*>) {
            isExpire = when (it) {
                is HttpDataResult<*> -> {
                    it.status == 503
                }

                is HttpSimpleResult<*> -> {
                    it.err?.code == 503 || it.err?.code == -7 || it.err?.code == 2003
                }

                else -> {
                    false
                }
            }
        }
    }
    if (isExpire) {
        UserInfoUtils.clearAll()
    }
    return isExpire
}

private fun Throwable.isNetworkException(): Boolean {
    return this is UnknownHostException ||
            this is SocketTimeoutException ||
            this is TimeoutException ||
            this is ConnectException ||
            this is SSLHandshakeException ||
            this is ProtocolException ||
            (this is IOException && this !is UnknownHostException) ||
            this is TimeoutCancellationException


}


private fun Throwable.toHttpException(): HttpException {
    if (this is HttpException) {
        return this
    }
    if (this is retrofit2.HttpException) {
        return HttpException(this.code(), this.message(), this)
    }
    return HttpException(-1, this.message, this)
}


/**
 * 手动重试网络弹窗
 * TODO 需要实现
 * @return Boolean
 */
private suspend fun invokeShowNetPopup(): Boolean {
    return false
//    return suspendCancellableCoroutine { continuation ->
//        NetworkFailurePopup(UiKit.getApplication()).apply {
//            context?.let {
//                it.dismissLoading()
//            }
//            setOnRightClickListener {
//                LogUtils.i("invokeShowNetPopup 点击了重试 $context")
//                context?.let {
//                    it.showLoading(0)
//                }
//                continuation.resume(true)
//                dismiss()
//            }
//            onDismissListener = object : BasePopupWindow.OnDismissListener() {
//                override fun onDismiss() {
//                    LogUtils.i("invokeShowNetPopup onDismissListener $context")
//                    //continuation.resume(false)
//                }
//            }
//        }.also { dialog->
//            continuation.invokeOnCancellation {
//                dialog.dismiss()
//            }
//        }.showPopupWindow()
//    }
}


/**
 * TODO 登陆接口
 */
suspend fun invokeLoginRequest(): Result<HttpResultInterface<*>>? {
    if (UserInfoUtils.isLogin()) {
        return null
    }
    return null
//    var result :Result<HttpResultInterface<*>>? = null
//    runCatching {
//        val deviceSn = UserInfoUtils.getDeviceSn().orEmpty()
//        val sign = StringUtils.getSign(deviceSn, Constants.PARTNER_ID)
//        val url = "https://apiv3-preview.gankao.com/login/device$sign"
//        CommonApi.getApi().loginDevice(url)
//    }.onSuccess {
//        if (it?.status == 200){
//            it.data?.let {
//                UserInfoHelper.setUserInfo(it)
//            }
//        }else {
//            result = Result.failure(Exception("登录失败: ${it?.msg}"))
//        }
//    }.onFailure {
//        result = Result.failure(it)
//    }
//    return result
}


