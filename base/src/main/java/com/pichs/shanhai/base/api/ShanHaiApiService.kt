package com.pichs.shanhai.base.api

import androidx.annotation.Keep
import com.pichs.shanhai.base.api.entity.BaseData
import com.pichs.shanhai.base.api.entity.BaseResponse
import com.pichs.shanhai.base.api.entity.UpdateAppInfo
import com.pichs.shanhai.base.api.entity.UserInfo
import retrofit2.http.Body
import retrofit2.http.POST

@Keep
interface ShanHaiApiService {

    @POST(ShanHaiApi.LOGIN)
    suspend fun login(@Body body: LoginBody): BaseData<UserInfo>?

    @POST(ShanHaiApi.USER_INFO)
    suspend fun getUserInfo(): BaseData<UserInfo>?

    @POST(ShanHaiApi.DESTROY_ACCOUNT)
    suspend fun destroyAccount(): BaseData<Any>?
}

@Keep
data class UpdateAppBody(
    var type: Int = 0,
    var category_type: String = "1,3",
)
@Keep
data class LoginBody(
    var account: String? = null,
    var password: String? = null
)