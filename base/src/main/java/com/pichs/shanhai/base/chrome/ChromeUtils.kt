package com.pichs.shanhai.base.chrome

import android.content.Context
import android.util.Log
import com.pichs.shanhai.base.consts.ChromeConst
import com.pichs.shanhai.base.route.RouteUtils.build
import com.pichs.shanhai.base.route.RouterPath

/**
 * @Description: 内置浏览器工具类
 */
object ChromeUtils {
    /**
     * 从gaga内部跳转过去的
     *
     * @param context 上下文
     * @param url     网址
     */
    @JvmStatic
    fun jumpToChrome(context: Context?, url: String?) {
        jumpToChrome(context, url, 0, "", "")
    }

    /**
     * 从gaga内部跳转过去的
     *
     * @param context  上下文
     * @param url      网址
     * @param title    title
     * @param imageUrl imageUrl
     */
    @JvmStatic
    fun jumpToChrome(context: Context?, url: String?, title: String?, imageUrl: String?) {
        jumpToChrome(context, url, 0, title, imageUrl)
    }

    /**
     * 跳转内置浏览器
     *
     * @param context     上下文
     * @param content     内容 或者 网址
     * @param contentType 内容类型 -- 0：网址，1：其他（其他暂时没用，预留）
     * @param title       标题
     * @param imageUrl    图片网址
     */
    @JvmStatic
    fun jumpToChrome(context: Context?, content: String?, contentType: Int, title: String?, imageUrl: String?) {
        try {
            if (content == null) {
                return
            }
            build(RouterPath.CHROME_PAGE)
                .withString(ChromeConst.KEY_CONTENT, content)
                .withString(ChromeConst.KEY_TITLE, title)
                .withInt(ChromeConst.KEY_CONTENT_TYPE, contentType)
                .navigation(context)
        } catch (e: Exception) {
            Log.e("ChromeUtils", "jumpToChrome: " + e.message)
        }
    }
}
