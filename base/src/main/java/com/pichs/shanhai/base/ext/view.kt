package com.pichs.shanhai.base.ext

import android.view.View

fun View.click(block: (View) -> Unit) {
    setOnClickListener { block(it) }
}

fun View.click(listener: View.OnClickListener) {
    setOnClickListener(listener)
}

fun View.longClick(block: (View) -> Boolean) {
    setOnLongClickListener { block(it) }
}

fun View.longClick(listener: View.OnLongClickListener) {
    setOnLongClickListener(listener)
}