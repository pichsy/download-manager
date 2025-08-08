package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.drake.brv.utils.grid
import com.drake.brv.utils.setup
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.pichs.download.core.DownloadManager
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.demo.databinding.ActivityMainBinding
import com.pichs.download.demo.databinding.ItemGridDownloadBeanBinding
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.utils.GsonUtils
import java.io.File

class MainActivity : BaseActivity<ActivityMainBinding>() {

    private val list = arrayListOf<DownloadItem>()

    override fun afterOnCreate() {
        XXPermissions.with(this)
            .unchecked()
            .permission(Permission.MANAGE_EXTERNAL_STORAGE)
            .permission(Permission.REQUEST_INSTALL_PACKAGES)
            .request { _, _ -> }

        initListener()

        val appJsonStr = assets.open("app_list.json").bufferedReader().use { it.readText() }
        val appListBean = GsonUtils.fromJson<AppListBean>(appJsonStr, AppListBean::class.java)
        appListBean.appList?.let { list.addAll(it) }

        initRecyclerView()
    }

    private fun initListener() {
        binding.ivDownloadManager.setOnClickListener {
            startActivity(Intent(this, DownloadManagerActivity::class.java))
        }

        binding.ivSearch.setOnClickListener { ToastUtils.show("搜索") }
    }

    @SuppressLint("SetTextI18n")
    private fun initRecyclerView() {
        binding.recyclerView.grid(4).setup {
            addType<DownloadItem>(R.layout.item_grid_download_bean)

            onBind {
                val item = getModel<DownloadItem>()
                val vb = getBinding<ItemGridDownloadBeanBinding>()
                if (!item.icon.isNullOrEmpty()) {
                    Glide.with(vb.ivCover).load(item.icon).into(vb.ivCover)
                } else {
                    vb.ivCover.setImageResource(R.color.purple_200)
                }
                vb.tvAppName.text = item.name
                bindButtonUI(vb, item.task)
                vb.btnDownload.setOnClickListener { handleClick(item, vb) }
            }
        }.models = list
    }

    private fun handleClick(item: DownloadItem, vb: ItemGridDownloadBeanBinding) {
        val task = item.task
        when (task?.status) {
            DownloadStatus.DOWNLOADING -> DownloadManager.pause(task.id)
            DownloadStatus.PAUSED -> DownloadManager.resume(task.id)
            DownloadStatus.COMPLETED -> openApk(task)
            DownloadStatus.FAILED -> startDownload(item, vb)
            else -> startDownload(item, vb)
        }
    }

    private fun startDownload(item: DownloadItem, vb: ItemGridDownloadBeanBinding) {
        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        val task = DownloadManager.download(item.url)
            .to(dir, item.name)
            .onProgress { progress, _ ->
                vb.btnDownload.setProgress(progress)
                vb.btnDownload.setText("$progress%")
            }
            .onComplete { file ->
                vb.btnDownload.setProgress(100)
                vb.btnDownload.setText("安装")
                openApkFile(file)
            }
            .onError {
                vb.btnDownload.setText("重试")
            }
            .start()
        item.task = task
        bindButtonUI(vb, task)
    }

    private fun openApk(task: DownloadTask) {
        val file = File(task.filePath, task.fileName)
        openApkFile(file)
    }

    private fun openApkFile(file: File) {
        if (!file.exists()) return
        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        startActivity(intent)
    }

    private fun bindButtonUI(vb: ItemGridDownloadBeanBinding, task: DownloadTask?) {
        when (task?.status) {
            DownloadStatus.DOWNLOADING -> {
                vb.btnDownload.setText("暂停")
                vb.btnDownload.setProgress(task.progress)
            }
            DownloadStatus.PAUSED -> {
                vb.btnDownload.setText("继续")
                vb.btnDownload.setProgress(task.progress)
            }
            DownloadStatus.COMPLETED -> {
                vb.btnDownload.setText("安装")
                vb.btnDownload.setProgress(100)
            }
            DownloadStatus.FAILED -> vb.btnDownload.setText("重试")
            else -> {
                vb.btnDownload.setText("下载")
                vb.btnDownload.setProgress(0)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.recyclerView.post { binding.recyclerView.grid(7).adapter?.notifyDataSetChanged() }
        } else {
            binding.recyclerView.grid(4).adapter?.notifyDataSetChanged()
        }
    }
}