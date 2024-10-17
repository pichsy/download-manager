package com.pichs.shanhai.base.api.entity

import com.pichs.shanhai.base.http.result.HttpResultInterface

data class BaseData<T>(
    var status: Int = 0,
    var message: String = "",
    var data: T? = null,
    var code: Int = 0
): HttpResultInterface<T>
