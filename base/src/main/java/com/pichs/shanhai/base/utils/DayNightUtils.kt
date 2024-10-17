package com.pichs.shanhai.base.utils

import android.app.Activity
import com.pichs.xbase.utils.StatusBarUtils

object DayNightUtils {


    fun setStatusBarFontByDayNightMode(activity: Activity){
        // 判断系统是否是夜间模式
        val isDark = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        StatusBarUtils.setStatusBarFontDark(activity.window, !isDark)
    }


}