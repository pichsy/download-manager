package com.pichs.shanhai.base.utils.toast

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import com.hjq.toast.style.BlackToastStyle
import com.pichs.shanhai.base.R
import com.pichs.xbase.kotlinext.dp
import com.pichs.xbase.kotlinext.res2Color

class ShanhaiToastStyle : BlackToastStyle() {

    /**
     * 底部居中
     */
    override fun getGravity(): Int {
        return Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    override fun getBackgroundDrawable(context: Context?): Drawable {
        val drawable = GradientDrawable()
        drawable.setColor(R.color.toast_background_color.res2Color())
        drawable.cornerRadius = 30.dp.toFloat()
        return drawable
    }

    override fun getYOffset(): Int {
        return 40.dp
    }

}