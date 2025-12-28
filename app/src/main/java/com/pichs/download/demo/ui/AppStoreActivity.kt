package com.pichs.download.demo.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.pichs.download.demo.AppUseDataSettingsActivity
import com.pichs.download.demo.databinding.ActivityAppStoreBinding
import com.pichs.download.demo.ui.AppStoreFragment.Companion.TYPE_MUST_DOWNLOAD
import com.pichs.download.demo.ui.AppStoreFragment.Companion.TYPE_USER_DOWNLOAD
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.xbase.clickhelper.fastClick
import kotlin.getValue

class AppStoreActivity : BaseActivity<ActivityAppStoreBinding>() {

    private val fragments = mutableListOf<Fragment>()

    @SuppressLint("SourceLockedOrientationActivity")
    override fun beforeOnCreate(savedInstanceState: Bundle?) {
        super.beforeOnCreate(savedInstanceState)
    }

    override fun afterOnCreate() {
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