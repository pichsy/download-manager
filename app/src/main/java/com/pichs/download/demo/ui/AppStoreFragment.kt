package com.pichs.download.demo.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.AppUtils
import com.pichs.download.demo.CellularConfirmDialogActivity
import com.pichs.download.demo.CellularConfirmViewModel
import com.pichs.download.demo.FormatUtils
import com.pichs.download.demo.R
import com.pichs.download.demo.databinding.FragmentAppStoreBinding
import com.pichs.download.demo.utils.ApkInstallUtils
import com.pichs.download.demo.widget.ProgressButton
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.utils.NetworkUtils
import com.pichs.download.utils.SpeedUtils
import com.pichs.shanhai.base.api.entity.UpdateAppInfo
import com.pichs.shanhai.base.api.entity.qiniuHostUrl
import com.pichs.shanhai.base.base.BaseFragment
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.kotlinext.setItemAnimatorDisable
import com.pichs.xbase.utils.SysOsUtils
import com.pichs.xbase.utils.UiKit
import com.pichs.xwidget.cardview.XCardImageView
import com.pichs.xwidget.utils.XColorHelper
import com.pichs.xwidget.view.XTextView
import com.pichs.download.demo.install.InstallManager
import com.pichs.download.demo.install.InstallStatus
import com.pichs.download.demo.install.InstallTask
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class AppStoreFragment : BaseFragment<FragmentAppStoreBinding>() {

    private var type = TYPE_MUST_DOWNLOAD

    private val viewModel by viewModels<AppStoreViewModel>()

    private val appList = mutableListOf<UpdateAppInfo>()
    private lateinit var adapter: AppStoreAdapter

    // 进度更新防抖
    private val lastProgressUpdateTimeMap = mutableMapOf<String, Long>()
    private val progressUpdateInterval = 300L

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
        bindFlowListener()
        bindUiEvent()
        bindInstallListener()

        viewModel.loadUpdateAppList(type)
    }

    private fun initDataFlow() {
        lifecycleScope.launch {
            viewModel.appListFlow.collectLatest { list ->
                appList.clear()
                appList.addAll(list)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun initRecyclerView() {
        adapter = AppStoreAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.setItemAnimatorDisable()
        binding.recyclerView.adapter = adapter
    }

    private fun bindFlowListener() {
        DownloadManager.flowListener.bindToLifecycle(
            lifecycleOwner = viewLifecycleOwner,
            onTaskProgress = { task, progress, speed ->
                updateTaskWithProgress(task, progress, speed)
            },
            onTaskComplete = { task, file ->
                updateTask(task)
                // 下载完成，自动加入安装队列
                addToInstallQueue(task)
            },
            onTaskError = { task, error ->
                updateTask(task)
            },
            onTaskPaused = { task ->
                updateTask(task)
            },
            onTaskResumed = { task ->
                updateTask(task)
            },
            onTaskCancelled = { task ->
                updateTask(task)
            }
        )
    }

    private val installListener = object : InstallManager.InstallListener {
        override fun onInstallStatusChanged(packageName: String, status: InstallStatus) {
            // 更新对应包名的 UI
            val index = appList.indexOfFirst { it.package_name == packageName }
            if (index >= 0) {
                binding.recyclerView.post {
                    adapter.notifyItemChanged(index)
                }
            }
        }
    }

    private fun bindInstallListener() {
        InstallManager.addListener(installListener)
    }

    override fun onDestroyView() {
        InstallManager.removeListener(installListener)
        super.onDestroyView()
    }

    private fun bindUiEvent() {
        lifecycleScope.launch {
            viewModel.uiEvent.collect { event ->
                when (event) {
                    is AppStoreUiEvent.ShowToast -> {
                        ToastUtils.show(event.message)
                    }

                    is AppStoreUiEvent.ShowCellularConfirmDialog -> {
                        // 保存待执行的下载操作
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.confirmDownload(requireContext(), event.appInfo)
                        }
                        CellularConfirmDialogActivity.start(
                            requireContext(),
                            event.totalSize,
                            1
                        )
                    }

                    is AppStoreUiEvent.ShowWifiOnlyDialog -> {
                        // 仅WiFi模式弹窗
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.startDownloadAndPause(requireContext(), event.appInfo)
                        }
                        CellularConfirmDialogActivity.start(
                            requireContext(),
                            event.appInfo.size ?: 0L,
                            1,
                            CellularConfirmDialogActivity.MODE_WIFI_ONLY
                        )
                    }

                    is AppStoreUiEvent.ShowNoNetworkDialog -> {
                        // 无网络弹窗
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.startDownloadAndPauseForNetwork(requireContext(), event.appInfo)
                        }
                        CellularConfirmDialogActivity.start(
                            requireContext(),
                            event.appInfo.size ?: 0L,
                            1,
                            CellularConfirmDialogActivity.MODE_NO_NETWORK
                        )
                    }
                }
            }
        }
    }

    private fun updateTask(task: DownloadTask) {
        val index = appList.indexOfFirst { it.task?.id == task.id || it.apk_url?.qiniuHostUrl == task.url }
        if (index >= 0) {
            appList[index].task = task
            adapter.notifyItemChanged(index)
        }
    }

    private fun updateTaskWithProgress(task: DownloadTask, progress: Int, speed: Long) {
        // 防抖: 100% 不防抖
        if (progress < 100 && task.status != DownloadStatus.COMPLETED) {
            val now = System.currentTimeMillis()
            val lastTime = lastProgressUpdateTimeMap[task.id] ?: 0L
            if (now - lastTime < progressUpdateInterval) {
                return
            }
            lastProgressUpdateTimeMap[task.id] = now
        } else {
            lastProgressUpdateTimeMap.remove(task.id)
        }

        val index = appList.indexOfFirst { it.task?.id == task.id || it.apk_url?.qiniuHostUrl == task.url }
        if (index >= 0) {
            appList[index].task = task
            adapter.notifyItemChanged(index, "PROGRESS_UPDATE")
        }
    }

    private fun onDownloadClick(appInfo: UpdateAppInfo, position: Int) {
        val task = appInfo.task
        val ctx = requireContext()

        // 检查是否已安装
        val pkg = appInfo.package_name ?: ""
        val storeVC = appInfo.version_code ?: 0L
        if (pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(ctx, pkg, storeVC)) {
            AppUtils.openApp(ctx, pkg)
            return
        }

        when (task?.status) {
            null -> {
                // 无任务，使用预检查流程开始下载
                viewModel.requestDownload(ctx, appInfo)
            }

            DownloadStatus.DOWNLOADING -> {
                viewModel.pauseDownload(task.id)
            }

            DownloadStatus.PAUSED -> {
                if (!NetworkUtils.isNetworkAvailable(ctx)) {
                    android.widget.Toast.makeText(ctx, "网络不可用，请检查网络后重试", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.resumeDownload(task.id)
                }
            }

            DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                viewModel.pauseDownload(task.id)
            }

            DownloadStatus.FAILED -> {
                if (!NetworkUtils.isNetworkAvailable(ctx)) {
                    android.widget.Toast.makeText(ctx, "网络不可用，请检查网络后重试", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // 失败时重新走预检查流程
                    viewModel.requestDownload(ctx, appInfo)
                }
            }

            DownloadStatus.COMPLETED -> {
                // 检查安装状态
                val pkg = appInfo.package_name ?: ""
                val installStatus = InstallManager.getInstallStatus(pkg)
                
                when (installStatus) {
                    InstallStatus.INSTALLING, InstallStatus.PENDING -> {
                        // 正在安装或等待安装，不处理点击
                    }
                    else -> {
                        // 已安装成功或不在队列中
                        if (ApkInstallUtils.checkAppInstalled(ctx, pkg)) {
                            // 已安装，打开应用
                            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                            }
                        } else {
                            // 未安装，加入安装队列进行静默安装
                            addToInstallQueue(task)
                            // 刷新当前 item UI
                            val index = appList.indexOfFirst { it.package_name == pkg }
                            if (index >= 0) {
                                adapter.notifyItemChanged(index)
                            }
                        }
                    }
                }
            }

            else -> {}
        }
    }

    /**
     * 将下载完成的任务加入安装队列
     */
    private fun addToInstallQueue(task: DownloadTask) {
        // 从 extras 获取包名和版本号
        val extras = task.extras
        if (extras.isNullOrBlank()) return
        
        try {
            val meta = com.pichs.download.demo.ExtraMeta.fromJson(extras)
            val packageName = meta?.packageName ?: return
            val versionCode = meta.versionCode ?: 0L
            
            val apkFile = File(task.filePath, task.fileName)
            if (!apkFile.exists()) return
            
            // 检查本地版本是否已是最新
            val localVC = SysOsUtils.getVersionCode(requireContext(), packageName)
            if (localVC >= versionCode) {
                // 已是最新版本，不需要安装
                return
            }
            
            // 检查是否已在队列中
            if (InstallManager.isInQueue(packageName)) return
            
            InstallManager.addToQueue(
                InstallTask(
                    packageName = packageName,
                    apkPath = apkFile.absolutePath,
                    versionCode = versionCode
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ===================== Inner Adapter =====================

    private inner class AppStoreAdapter : RecyclerView.Adapter<AppStoreVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppStoreVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_store_cells, parent, false)
            return AppStoreVH(v)
        }

        override fun getItemCount(): Int = appList.size

        override fun onBindViewHolder(holder: AppStoreVH, position: Int) {
            holder.bind(appList[position], position)
        }

        override fun onBindViewHolder(holder: AppStoreVH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) {
                holder.updateProgress(appList[position])
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }
    }

    private inner class AppStoreVH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val ivIcon: XCardImageView = itemView.findViewById(R.id.iv_icon)
        private val tvAppName: XTextView = itemView.findViewById(R.id.tv_app_name)
        private val tvAppSize: XTextView = itemView.findViewById(R.id.tv_app_size)
        private val tvAppVersion: XTextView = itemView.findViewById(R.id.tv_app_version)
        private val tvTag: com.pichs.xwidget.roundview.XRoundTextView = itemView.findViewById(R.id.tv_tag)
        private val btnUpdate: ProgressButton = itemView.findViewById(R.id.btn_update)

        private var currentPosition: Int = -1
        private var originalSize: String = ""

        init {
            btnUpdate.setOnClickListener {
                if (currentPosition >= 0 && currentPosition < appList.size) {
                    onDownloadClick(appList[currentPosition], currentPosition)
                }
            }
        }

        fun bind(appInfo: UpdateAppInfo, position: Int) {
            currentPosition = position
            val ctx = itemView.context
            val task = appInfo.task

            // 图标
            val iconUrl = appInfo.app_icon?.qiniuHostUrl
            if (!iconUrl.isNullOrBlank()) {
                Glide.with(ivIcon).load(iconUrl).into(ivIcon)
            } else {
                ivIcon.setImageResource(R.color.purple_200)
            }

            // 标题
            tvAppName.text = appInfo.app_name ?: "未知应用"

            // 大小 (保存原始大小用于恢复)
            originalSize = "大小:${FormatUtils.formatFileSize(appInfo.size ?: 0L)}"
            tvAppSize.text = originalSize


            // 检查本地安装情况
            val pkg = appInfo.package_name ?: ""
            val storeVC = appInfo.version_code ?: 0L

            // 版本
            tvAppVersion.text = "版本:${appInfo.version_name ?: "1.0.0"}"

            // 获取本地版本号（0 表示未安装）
            val localVC = SysOsUtils.getVersionCode(UiKit.getApplication(), pkg)
            val isInstalled = ApkInstallUtils.checkAppInstalled(UiKit.getApplication(), pkg)

            val isLatestVersion = isInstalled && localVC >= storeVC  // 是否已是最新版本
            val needsUpdate = isInstalled && localVC < storeVC  // 需要更新

            // 按钮和标签状态 - 优先判断版本号
            if (isLatestVersion) {
                // 已是最新版本，直接显示打开（不管 task 状态）
                btnUpdate.visibility = View.VISIBLE
                btnUpdate.setText("打开")
                btnUpdate.setProgress(100)
                tvTag.text = "已是最新版本"
                tvTag.visibility = View.VISIBLE
                tvTag.setTextColor(XColorHelper.parseColor("#FF06BB8D"))
                tvTag.setNormalBackgroundColor(XColorHelper.parseColor("#FFEDFEF9"))
                tvAppSize.text = originalSize
            } else {
                // 不是最新版本，根据 task 状态判断
                when (task?.status) {
                    null -> {
                        if (needsUpdate) {
                            // 已安装但需要更新
                            btnUpdate.visibility = View.VISIBLE
                            btnUpdate.setText("更新")
                            btnUpdate.setProgress(0)
                            tvTag.text = "有新版本"
                            tvTag.visibility = View.VISIBLE
                            tvTag.setTextColor(XColorHelper.parseColor("#FFFF9337"))
                            tvTag.setNormalBackgroundColor(XColorHelper.parseColor("#FFFFF4EB"))
                        } else {
                            // 未安装
                            btnUpdate.visibility = View.VISIBLE
                            btnUpdate.setText("下载")
                            btnUpdate.setProgress(0)
                            tvTag.text = "未安装"
                            tvTag.visibility = View.VISIBLE
                            tvTag.setTextColor(XColorHelper.parseColor("#FFFF9337"))
                            tvTag.setNormalBackgroundColor(XColorHelper.parseColor("#FFFFF4EB"))
                        }
                        tvAppSize.text = originalSize
                    }

                    DownloadStatus.DOWNLOADING -> {
                        btnUpdate.visibility = View.VISIBLE
                        btnUpdate.setText("${task.progress}%")
                        btnUpdate.setProgress(task.progress)
                        tvAppSize.text = SpeedUtils.formatDownloadSpeed(task.speed)
                        tvTag.visibility = View.GONE
                    }

                    DownloadStatus.PAUSED -> {
                        btnUpdate.visibility = View.VISIBLE
                        btnUpdate.setText("继续")
                        btnUpdate.setProgress(task.progress)
                        tvAppSize.text = originalSize
                        tvTag.visibility = View.GONE
                    }

                    DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                        btnUpdate.visibility = View.VISIBLE
                        btnUpdate.setText("等待中")
                        btnUpdate.setProgress(task.progress)
                        tvAppSize.text = originalSize
                        tvTag.text = "排队中"
                        tvTag.visibility = View.VISIBLE
                    }

                    DownloadStatus.FAILED -> {
                        btnUpdate.visibility = View.VISIBLE
                        btnUpdate.setText("重试")
                        btnUpdate.setProgress(task.progress)
                        tvAppSize.text = originalSize
                        tvTag.text = "失败"
                        tvTag.visibility = View.VISIBLE
                    }

                    DownloadStatus.COMPLETED -> {
                        btnUpdate.visibility = View.VISIBLE
                        val health = AppUtils.checkFileHealth(task)
                        val pkg = appInfo.package_name ?: ""
                        
                        if (health == AppUtils.FileHealth.OK) {
                            // 检查安装状态
                            val installStatus = InstallManager.getInstallStatus(pkg)
                            
                            when (installStatus) {
                                InstallStatus.INSTALLING -> {
                                    // 正在安装
                                    btnUpdate.setText("安装中")
                                    btnUpdate.isEnabled = false
                                    tvTag.text = "安装中"
                                    tvTag.visibility = View.VISIBLE
                                    tvTag.setTextColor(XColorHelper.parseColor("#FFFF9337"))
                                    tvTag.setNormalBackgroundColor(XColorHelper.parseColor("#FFFFF4EB"))
                                }
                                InstallStatus.PENDING -> {
                                    // 等待安装
                                    btnUpdate.setText("等待安装")
                                    btnUpdate.isEnabled = false
                                    tvTag.text = "排队中"
                                    tvTag.visibility = View.VISIBLE
                                    tvTag.setTextColor(XColorHelper.parseColor("#FFFF9337"))
                                    tvTag.setNormalBackgroundColor(XColorHelper.parseColor("#FFFFF4EB"))
                                }
                                else -> {
                                    // 不在安装队列中，显示安装按钮（不自动加入队列）
                                    btnUpdate.setText("安装")
                                    btnUpdate.isEnabled = true
                                    tvTag.text = "待安装"
                                    tvTag.visibility = View.VISIBLE
                                    tvTag.setTextColor(XColorHelper.parseColor("#FFFF9337"))
                                    tvTag.setNormalBackgroundColor(XColorHelper.parseColor("#FFFFF4EB"))
                                }
                            }
                        } else {
                            // 文件损坏或缺失
                            btnUpdate.setText("下载")
                            tvTag.text = "待更新"
                            tvTag.visibility = View.VISIBLE
                            tvTag.setTextColor(XColorHelper.parseColor("#FFFF9337"))
                            tvTag.setNormalBackgroundColor(XColorHelper.parseColor("#FFFFF4EB"))
                        }
                        btnUpdate.setProgress(100)
                        tvAppSize.text = originalSize
                    }

                    else -> {
                        btnUpdate.visibility = View.VISIBLE
                        btnUpdate.setText("下载")
                        btnUpdate.setProgress(0)
                        tvAppSize.text = originalSize
                        tvTag.text = "待更新"
                        tvTag.visibility = View.VISIBLE
                    }
                }
            }
            btnUpdate.isEnabled = true
        }

        fun updateProgress(appInfo: UpdateAppInfo) {
            val task = appInfo.task ?: return
            btnUpdate.visibility = View.VISIBLE
            // 更新按钮进度
            btnUpdate.setProgress(task.progress)
            btnUpdate.setText("${task.progress}%")
            // 大小控件显示网速
            tvAppSize.text = SpeedUtils.formatDownloadSpeed(task.speed)
            tvTag.visibility = View.GONE
        }
    }
}