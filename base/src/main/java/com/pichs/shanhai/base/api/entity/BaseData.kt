package com.pichs.shanhai.base.api.entity

import com.pichs.shanhai.base.http.result.HttpResultInterface

data class BaseData<T>(
    var status: Int = 0,
    var message: String = "",
    var data: T? = null,
    var code: Int = 0
) : HttpResultInterface<T>

data class BaseResponse<T>(
    var err: BaseResponseError? = null,
    var result: BaseResponseData<T>?,
)

data class BaseResponseData<T>(
    var data: T? = null,
    var msg: String? = null,
    var status: Int = 0,
)

data class BaseResponseError(
    var message: String? = null,
    var stack: String? = null,
    var code: Int? = 0,
)