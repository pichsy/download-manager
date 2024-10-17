package com.pichs.shanhai.base.user

import com.pichs.shanhai.base.api.entity.UserInfo
import com.pichs.xbase.cache.BaseMMKVHelper

object UserInfoUtils : BaseMMKVHelper("shanhai_user_info") {

    fun getUid(): String {
        return getString("uid", "") ?: ""
    }

    fun saveUid(uid: String?) {
        setString("uid", uid)
    }

    fun clearUid() {
        remove("uid")
    }

    fun getToken(): String {
        return getString("token", "") ?: ""
    }

    fun saveToken(token: String?) {
        if (token.isNullOrEmpty()) return
        setString("token", token)
    }

    fun clearToken() {
        remove("token")
    }

    fun saveUserInfo(user: UserInfo?) {
        setObject("user", user)
    }

    fun getUserInfo(): UserInfo? {
        return getObject("user", UserInfo::class.java)
    }

    fun clearUserInfo() {
        remove("user")
    }

    fun isLogin(): Boolean {
        return getToken().isNotEmpty()
    }
}