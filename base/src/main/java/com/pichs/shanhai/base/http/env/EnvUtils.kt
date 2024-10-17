package com.pichs.shanhai.base.http.env

import com.pichs.xbase.cache.CacheHelper

object EnvUtils {

    const val ENV_PREVIEW = "preview"
    const val ENV_RELEASE = "release"
    const val ENV_TEST = "test"

    fun changeEnv(env: String) {
        CacheHelper.get().setString("gk_dpc_env", env)
    }

    /**
     *  切换 api 环境  如果未获取到，默认 release 环境
     *  @return api 环境: 正式环境=release ,预发布环境=preview
     */
    fun getEnv(): String {
        return CacheHelper.get().getString("gk_dpc_env", ENV_RELEASE) ?: ENV_RELEASE
    }

    fun isReleaseEnv(): Boolean {
        return getEnv() == ENV_RELEASE
    }

    fun getEnvName(): String {
        return when (getEnv()) {
            ENV_PREVIEW -> "预发布环境"
            ENV_RELEASE -> "正式环境"
            ENV_TEST -> "测试环境"
            else -> "正式环境"
        }
    }

    /**
     * 判断是否切换了环境
     */
    fun isEnvChanged(): Boolean {
        return !CacheHelper.get().getString("gk_dpc_env", null).isNullOrEmpty()
    }
}