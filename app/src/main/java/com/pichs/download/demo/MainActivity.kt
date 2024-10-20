package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Environment
import androidx.lifecycle.lifecycleScope
import com.drake.brv.utils.linear
import com.drake.brv.utils.setup
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.pichs.download.DownloadTask
import com.pichs.download.Downloader
import com.pichs.download.call.DownloadMultiCall
import com.pichs.download.callback.DownloadListener
import com.pichs.download.demo.databinding.ActivityMainBinding
import com.pichs.download.demo.databinding.ItemDonwloadListBinding
import com.pichs.download.dispatcher.DispatcherListener
import com.pichs.download.utils.TaskIdUtils
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.ext.click
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.shanhai.base.utils.toast.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : BaseActivity<ActivityMainBinding>() {


    val zhishangtanbing = "https://downali.wandoujia.com/s/3/3/20230112163122_3724e0cf162b42b8a9312e1a37b96436_().apk?"

    val bizhiduoduo = "https://ucdl.25pp.com/fs08/2024/10/10/5/106_7efa85efaf878e6e860968bc8d5ddcf3.apk"

    private val list = arrayListOf<DownloadItem>()

    override fun afterOnCreate() {

        XXPermissions.with(this)
            .unchecked()
            .permission(Permission.WRITE_EXTERNAL_STORAGE)
            .permission(Permission.READ_EXTERNAL_STORAGE)
            .request { permissions, allGranted ->

            }

        initListener()

        list.add(
            DownloadItem(url = bizhiduoduo, name = "bizhiduoduo", packageName = "com.didichuxing.doraemonkit"),
        )

        list.add(
            DownloadItem(url = zhishangtanbing, name = "zhishangtanbing", packageName = "com.tencent.zhishangtanbing"),
        )

        lifecycleScope.launch {
            Downloader.with().queryAllTasksFromCache().forEach {
                LogUtils.d("download666", "查询到的下载任务:$it")
            }
        }
        initRecyclerView()
    }

    private fun initListener() {
        binding.ivDownloadManager.setOnClickListener {
            startActivity(Intent(this, DownloadManagerActivity::class.java))
        }

        binding.ivSearch.setOnClickListener {
            ToastUtils.show("搜索")
        }
    }


    @SuppressLint("SetTextI18n")
    private fun initRecyclerView() {
        binding.recyclerView.linear().setup {
            addType<DownloadItem>(R.layout.item_donwload_list)
            onBind {
                val item = getModel<DownloadItem>()
                val itemBinding = getBinding<ItemDonwloadListBinding>()
                itemBinding.tvAppName.text = item.name
            }
        }.models = list
    }


}