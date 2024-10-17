package com.pichs.shanhai.base.ext

import android.app.Activity
import android.content.Context
import android.content.Intent


fun Context.startActivitySafely(intent: Intent) {
    try {
        if (this !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}