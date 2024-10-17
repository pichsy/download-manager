package com.pichs.shanhai.base.http.result

interface HttpResultInterface<T>


fun HttpResultInterface<*>?.getResult(): Boolean {
    return this is HttpSimpleResult<*> && this.err == null
}
/**
 * 后端接口返回格式
 * @param T
 * @property result T
 * @property err ErrorData?
 * @constructor
 */
/**
 * HttpSimpleResult Node.js 返回结构
 */
data class HttpSimpleResult<T>(var result: T, var err: ErrorData?) : HttpResultInterface<T>

/**
 * PHP 返回结构
 */
data class HttpDataResult<T>(
    var data: T,
    val status: Int = 0,
    val msg: String? = null,
    val _adDic: Any? = null,
    val _env: Any? = null,
    val _pointData: PointData? = null,
    val hintInfo: Any?,
    val isEncryption: Any?,
    val serverTime: Any?,
) : HttpResultInterface<T>

data class ErrorData(var _gankao: Int, var code: Int = 0, var message: String?, var more: String?)

class PointData
