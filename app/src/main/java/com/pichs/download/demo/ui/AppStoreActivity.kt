package com.pichs.download.demo.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import android.provider.Settings
import android.net.Uri
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.AppUseDataSettingsActivity
import com.pichs.download.demo.MyCheckAfterCallback
import com.pichs.download.demo.databinding.ActivityAppStoreBinding
import com.pichs.download.demo.floatwindow.FloatBallHelper
import com.pichs.download.demo.ui.AppStoreFragment.Companion.TYPE_MUST_DOWNLOAD
import com.pichs.download.demo.ui.AppStoreFragment.Companion.TYPE_USER_DOWNLOAD
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.xbase.clickhelper.fastClick


class AppStoreActivity : BaseActivity<ActivityAppStoreBinding>() {

    private val fragments = mutableListOf<Fragment>()
    private val floatBallHelper by lazy { FloatBallHelper(this) }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun beforeOnCreate(savedInstanceState: Bundle?) {
        super.beforeOnCreate(savedInstanceState)
    }

    override fun afterOnCreate() {
        // 设置网络决策回调
        DownloadManager.setCheckAfterCallback(MyCheckAfterCallback(this))

        // 绑定生命周期并显示悬浮球
        floatBallHelper.bind(this)
        if (Settings.canDrawOverlays(this)) {
            floatBallHelper.show()
        } else {
            // 无悬浮窗权限，跳转设置页
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }

        iniView()
    }

    private fun iniView() {

        binding.ivBack.fastClick {
            finish()
        }

        binding.ivSettings.isVisible = true

        fragments.add(AppStoreFragment.newInstance(TYPE_MUST_DOWNLOAD))
        fragments.add(AppStoreFragment.newInstance(TYPE_USER_DOWNLOAD))

        initViewPager()

        binding.llTabGroup.setOnRadioCheckedListener { _, _, isChecked, position ->
            binding.viewPager2.currentItem = position
        }

        binding.ivSettings.fastClick {
            startActivity(Intent(this, AppUseDataSettingsActivity::class.java))
        }
    }


    private fun initViewPager() {
        binding.viewPager2.isUserInputEnabled = false
        binding.viewPager2.adapter = object : FragmentStateAdapter(supportFragmentManager, this.lifecycle) {
            override fun createFragment(position: Int): Fragment {
                return fragments[position]
            }

            override fun getItemCount(): Int {
                return fragments.size
            }
        }

        binding.viewPager2.currentItem = 0
    }


}