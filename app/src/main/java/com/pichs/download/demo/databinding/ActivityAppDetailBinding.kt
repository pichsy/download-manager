package com.pichs.download.demo.databinding.stub

// Manual stub moved to a separate package/name to avoid conflicts with ViewBinding.
// Not referenced by the app; safe to keep or delete.

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.pichs.download.demo.R
import com.pichs.xwidget.cardview.XCardButton
import com.pichs.xwidget.cardview.XCardImageView
import com.pichs.xwidget.view.XTextView
import com.pichs.download.demo.widget.ProgressButton

class ActivityAppDetailBindingStub private constructor(val root: View) {
    val btnBack: XCardButton = root.findViewById(R.id.btn_back)
    val ivIcon: XCardImageView = root.findViewById(R.id.iv_icon)
    val tvTitle: XTextView = root.findViewById(R.id.tv_title)
    val tvPackage: XTextView = root.findViewById(R.id.tv_package)
    val tvSize: XTextView = root.findViewById(R.id.tv_size)
    val tvDesc: XTextView = root.findViewById(R.id.tv_desc)
    val btnDownload: ProgressButton = root.findViewById(R.id.btn_download)

    companion object {
        fun inflate(inflater: LayoutInflater, parent: ViewGroup? = null, attachToParent: Boolean = false): ActivityAppDetailBindingStub {
            val view = inflater.inflate(R.layout.activity_app_detail, parent, attachToParent)
            return ActivityAppDetailBindingStub(view)
        }
    }
}
