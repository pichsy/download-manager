package com.pichs.download.demo

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.pichs.download.demo.databinding.ActivityDownloadManagerBinding
import com.pichs.shanhai.base.base.BaseActivity

class DownloadManagerActivity : BaseActivity<ActivityDownloadManagerBinding>() {

    private val fragments = listOf(
        DownloadingFragment(),
        CompletedFragment()
    )

    override fun afterOnCreate() {
        setupTitleBar()
        setupViewPager()
        setupTabs()
    }

    private fun setupTitleBar() {
        binding.ivBack.setOnClickListener {
            finish()
        }
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = DownloadPagerAdapter(this, fragments)
        binding.viewPager.offscreenPageLimit = 1
        
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTabState(position)
            }
        })
    }

    private fun setupTabs() {
        binding.tabDownloading.setOnClickListener {
            binding.viewPager.currentItem = 0
        }
        binding.tabCompleted.setOnClickListener {
            binding.viewPager.currentItem = 1
        }
        
        // 初始状态
        updateTabState(0)
    }

    private fun updateTabState(position: Int) {
        when (position) {
            0 -> {
                binding.tabDownloading.setTextColor(0xFF333333.toInt())
                binding.tabDownloading.paint.isFakeBoldText = true
                binding.tabCompleted.setTextColor(0x80333333.toInt())
                binding.tabCompleted.paint.isFakeBoldText = false
                
                binding.indicatorDownloading.setBackgroundColor(0xFF6366F1.toInt())
                binding.indicatorCompleted.setBackgroundColor(0x00000000)
            }
            1 -> {
                binding.tabDownloading.setTextColor(0x80333333.toInt())
                binding.tabDownloading.paint.isFakeBoldText = false
                binding.tabCompleted.setTextColor(0xFF333333.toInt())
                binding.tabCompleted.paint.isFakeBoldText = true
                
                binding.indicatorDownloading.setBackgroundColor(0x00000000)
                binding.indicatorCompleted.setBackgroundColor(0xFF6366F1.toInt())
            }
        }
        // 刷新文字显示
        binding.tabDownloading.invalidate()
        binding.tabCompleted.invalidate()
    }

    private class DownloadPagerAdapter(
        activity: FragmentActivity,
        private val fragments: List<Fragment>
    ) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = fragments.size
        override fun createFragment(position: Int): Fragment = fragments[position]
    }
}
