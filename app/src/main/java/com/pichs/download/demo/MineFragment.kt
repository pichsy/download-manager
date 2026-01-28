package com.pichs.download.demo

import android.content.Intent
import com.pichs.download.demo.databinding.FragmentMineBinding
import com.pichs.shanhai.base.base.BaseFragment
import com.pichs.xbase.kotlinext.fastClick
import com.pichs.shanhai.base.utils.toast.ToastUtils

class MineFragment : BaseFragment<FragmentMineBinding>() {

    override fun afterOnCreateView(rootView: android.view.View?) {
        initListeners()
    }

    private fun initListeners() {
        binding.itemDownloadManager.fastClick {
            startActivity(Intent(requireContext(), DownloadManagerActivity::class.java))
        }

        binding.itemSettings.fastClick {
             startActivity(Intent(requireContext(), CellularSettingsActivity::class.java))
        }

        binding.itemAbout.fastClick {
             startActivity(Intent(requireContext(), AboutUsActivity::class.java))
        }
    }
}
