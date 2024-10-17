package com.pichs.shanhai.base.http.result

data class HttpException(
    var code: Int?,
    private var msg: String? = null,
    private var throwable: Throwable? = null
) : java.lang.Exception(msg, throwable)