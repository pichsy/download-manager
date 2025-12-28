package com.pichs.download.demo.ui

import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.drake.brv.utils.linear
import com.pichs.download.demo.databinding.FragmentAppStoreBinding
import com.pichs.shanhai.base.base.BaseFragment
import com.pichs.xbase.kotlinext.setItemAnimatorDisable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AppStoreFragment : BaseFragment<FragmentAppStoreBinding>() {

    private var type = TYPE_MUST_DOWNLOAD

    private val viewModel by viewModels<AppStoreViewModel>()

    companion object {

        const val TYPE_MUST_DOWNLOAD = 1
        const val TYPE_USER_DOWNLOAD = 2

        fun newInstance(type: Int): AppStoreFragment {
            val fragment = AppStoreFragment()
            val args = android.os.Bundle()
            args.putInt("type", type)
            fragment.arguments = args
            return fragment
        }
    }

    override fun afterOnCreateView(rootView: View?) {
        type = arguments?.getInt("type") ?: TYPE_MUST_DOWNLOAD

        initRecyclerView()

        initDataFlow()

        viewModel.loadUpdateAppList(type)
    }

    private fun initDataFlow() {

        lifecycleScope.launch {
            launch {
                viewModel.appListFlow.collectLatest { appList ->
                    // 应用数据请求回调。
                    // 这里填充列表数据。


                }
            }
        }

    }

    private fun initRecyclerView() {
        binding.recyclerView.linear().setItemAnimatorDisable()

        // item 使用 R.layout.item_download_task 这个布局，展示数据和下载逻辑。参考 DownloadManagerActivity，
        // 但是数据是 接口数据viewModel.appListFlow.collectLatest 返回的

        // adapter需要 实现，参考 MainActivity

    }

}