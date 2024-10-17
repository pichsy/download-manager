package com.pichs.shanhai.base.api

import com.pichs.shanhai.base.api.entity.BaseData
import com.pichs.shanhai.base.api.entity.UserInfo
import retrofit2.http.Body
import retrofit2.http.POST

interface ShanHaiApiService {

    @POST(ShanHaiApi.LOGIN)
    suspend fun login(@Body body: LoginBody): BaseData<UserInfo>?

    @POST(ShanHaiApi.USER_INFO)
    suspend fun getUserInfo(): BaseData<UserInfo>?

    @POST(ShanHaiApi.DESTROY_ACCOUNT)
    suspend fun destroyAccount(): BaseData<Any>?

}

data class LoginBody(
    var account: String? = null,
    var password: String? = null
)