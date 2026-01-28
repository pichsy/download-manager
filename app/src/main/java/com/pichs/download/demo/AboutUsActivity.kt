package com.pichs.download.demo

import android.annotation.SuppressLint
import com.pichs.download.demo.databinding.ActivityAboutUsBinding
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.xbase.kotlinext.fastClick

class AboutUsActivity : BaseActivity<ActivityAboutUsBinding>() {

    @SuppressLint("SetTextI18n")
    override fun afterOnCreate() {
        binding.ivBack.fastClick { finish() }
        
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        binding.tvVersion.text = "Version ${packageInfo.versionName}"
    }
}
