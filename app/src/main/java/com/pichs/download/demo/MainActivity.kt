package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Environment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.drake.brv.utils.grid
import com.drake.brv.utils.linear
import com.drake.brv.utils.setup
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.pichs.download.DownloadTask
import com.pichs.download.Downloader
import com.pichs.download.call.DownloadMultiCall
import com.pichs.download.callback.DownloadListener
import com.pichs.download.callback.IDownloadListener
import com.pichs.download.demo.DownloadManagerActivity
import com.pichs.download.demo.databinding.ActivityMainBinding
import com.pichs.download.demo.databinding.ItemDonwloadListBinding
import com.pichs.download.demo.databinding.ItemGridDownloadBeanBinding
import com.pichs.download.dispatcher.DispatcherListener
import com.pichs.download.utils.TaskIdUtils
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.ext.click
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.kotlinext.fastClick
import com.pichs.xbase.utils.GsonUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : BaseActivity<ActivityMainBinding>() {

    private val list = arrayListOf<DownloadItem>()

    override fun afterOnCreate() {

        XXPermissions.with(this)
            .unchecked()
            .permission(Permission.WRITE_EXTERNAL_STORAGE)
            .permission(Permission.READ_EXTERNAL_STORAGE)
            .request { permissions, allGranted ->

            }

        initListener()

        val appJsonStr = assets.open("app_list.json").bufferedReader().use { it.readText() }
        val appListBean = GsonUtils.fromJson<AppListBean>(appJsonStr, AppListBean::class.java)
        appListBean.appList?.let { list.addAll(it) }


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
        binding.recyclerView.grid(4).setup {
            addType<DownloadItem>(R.layout.item_grid_download_bean)
            onBind {
                val item = getModel<DownloadItem>()
                val itemBinding = getBinding<ItemGridDownloadBeanBinding>()
                itemBinding.tvAppName.text = item.name ?: "未知应用"
                Glide.with(itemBinding.ivCover)
                    .load(item.icon ?: "https://android-artworks.25pp.com/fs08/2025/07/10/4/110_f93562a47e4623a0037084abae9f4cc3_con_130x130.png")
                    .into(itemBinding.ivCover)

                itemBinding.btnDownload.fastClick {
                    // 可能未进行下载。点击后添加任务中。
                    Downloader.Builder()
                        .setListener(OnDownloadListener())
                        .setDownloadTaskInfo {
                            url = item.url
                            filePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                            fileName = item.name + ".apk"
                        }
                        .build()
                        .also { item.task = it }
                        .pushTask()
                }
            }
        }.models = list
    }


    /**
     * 下载监听器
     */
    inner class OnDownloadListener : DownloadListener() {
        override fun onStart(task: DownloadTask?, totalLength: Long) {
            LogUtils.d(
                "download666",
                "下载进度 app:${task?.downloadInfo?.fileName}: 开始下载：${task?.downloadInfo?.filePath},${task?.downloadInfo?.url}, name:${task?.downloadInfo?.fileName},"
            )
        }

        override fun onComplete(task: DownloadTask?) {
            LogUtils.d("download666", "下载进度 app:${task?.downloadInfo?.fileName}: 下载完毕：${task?.downloadInfo?.filePath}")
        }

        override fun onProgress(task: DownloadTask?, currentLength: Long, totalLength: Long, progress: Int, speed: Long) {
            LogUtils.d(
                "download666",
                "下载进度 app:${task?.downloadInfo?.fileName}: $progress%, speed: $speed, currentLength: $currentLength, totalLength: $totalLength"
            )
        }

        override fun onCancel(task: DownloadTask?) {
            LogUtils.d("download666", "下载进度 app:${task?.downloadInfo?.fileName}: 下载取消")
        }

        override fun onError(task: DownloadTask?, e: Throwable?) {
            LogUtils.e("download666", "下载进度 app:${task?.downloadInfo?.fileName}: 下载失败: ${e?.message}", e)
        }
    }
}