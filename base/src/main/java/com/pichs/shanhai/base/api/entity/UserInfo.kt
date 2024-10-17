package com.pichs.shanhai.base.api.entity

data class UserInfo(
    var name: String? = null,
    var uid: String? = null,
    var phone: String? = null,
    var birthday: String? = null,
    var age: Int? = null,
    var token: String? = null,
    var tokenExpiredTime: Long? = null,
    var sex: Int? = null,
    var avatar: String? = null,
    var address: String? = null,
)
