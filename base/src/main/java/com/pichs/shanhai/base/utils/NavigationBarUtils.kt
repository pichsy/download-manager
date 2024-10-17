package com.pichs.shanhai.base.utils

import android.app.Activity
import com.pichs.xbase.utils.StatusBarUtils
import com.pichs.xbase.utils.UiKit
import com.pichs.xwidget.utils.XDeviceHelper
import com.pichs.xwidget.utils.XDisplayHelper

object NavigationBarUtils {

    fun hasNavigationBar(activity: Activity?): Boolean {
        val calH =
            (XDisplayHelper.getRealScreenHeight(UiKit.getApplication()) - XDisplayHelper.getScreenHeight(UiKit.getApplication()) - StatusBarUtils.getStatusBarHeight())
        val realH = XDisplayHelper.getNavigationBarHeight(UiKit.getApplication())
        if (activity != null) {
            if (activity.isInMultiWindowMode || activity.isInPictureInPictureMode) {
                return false
            }
        }
        if (calH == realH) {
            return true
        }
        val isTable = XDeviceHelper.isTablet(UiKit.getApplication())
        if (isTable && calH > 10) {
            return true
        }

        if ((calH - realH) > 0) {
            return true
        }
        return calH > 0 && realH > 0
    }

    fun getNavigationBarHeight(): Int {
        return XDisplayHelper.getNavigationBarHeight(UiKit.getApplication())
    }


}