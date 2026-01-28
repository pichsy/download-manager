package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import androidx.core.content.FileProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.drake.brv.utils.setup
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.FormatUtils.formatFileSize
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.demo.databinding.FragmentRankBinding
import com.pichs.download.demo.databinding.ItemAppStoreCellsBinding
import com.pichs.shanhai.base.base.BaseFragment
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.kotlinext.dp
import com.pichs.xbase.kotlinext.fastClick
import com.pichs.xbase.kotlinext.setItemAnimatorDisable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RankFragment : BaseFragment<FragmentRankBinding>() {

    private val viewModel: MainViewModel by activityViewModels()

    private val softwareList = arrayListOf<DownloadItem>()
    private val gameList = arrayListOf<DownloadItem>()

    // Flow监听器
    private val flowListener = DownloadManager.flowListener

    // Track adapters for the two pages
    private var softwareAdapter: com.drake.brv.BindingAdapter? = null
    private var gameAdapter: com.drake.brv.BindingAdapter? = null

    override fun afterOnCreateView(rootView: View?) {
        initViewPager()
        loadData()
        bindFlowListener()

        // Handle UI Events from ViewModel (shared)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiEvent.collect { event ->
                // Basic toast handling if needed, though HomeFragment handles most global dialogs now.
                // If we trigger downloads from here, we might need to handle dialogs too or rely on Activity scope events?
                // Activity scope events are consumed by HomeFragment if valid? No, SharedFlow is multicast.
                // We should replicate handling here or ensure Activity handles it.
                // For now, let's replicate basic handling.
                when (event) {
                    is UiEvent.ShowToast -> ToastUtils.show(event.message)
                    is UiEvent.ShowCellularConfirmDialog -> {
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.confirmBatchDownload(event.apps)
                        }
                        CellularConfirmDialog.show(event.totalSize, event.apps.size)
                    }

                    is UiEvent.ShowWifiOnlyDialog -> {
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.startDownloadAndPause(event.apps)
                        }
                        CellularConfirmDialog.show(event.totalSize, event.apps.size, CellularConfirmDialog.MODE_WIFI_ONLY)
                    }

                    is UiEvent.ShowNoNetworkDialog -> {
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.startDownloadAndPauseForNetwork(event.apps)
                        }
                        CellularConfirmDialog.show(event.totalSize, event.apps.size, CellularConfirmDialog.MODE_NO_NETWORK)
                    }
                }
            }
        }

        binding.ivDownloadManager.setOnClickListener {
            startActivity(Intent(requireContext(), DownloadManagerActivity::class.java))
        }
    }

    private fun initViewPager() {
        val titles = listOf("软件", "游戏")

        // Use a simple RecyclerView Adapter for ViewPager2
        binding.viewPager.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                // Return a holder containing a RecyclerView
                val rv = androidx.recyclerview.widget.RecyclerView(parent.context)
                rv.layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                rv.clipToPadding = false
                rv.setPadding(0, 8.dp, 0, 0)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(rv) {}
            }

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val rv = holder.itemView as androidx.recyclerview.widget.RecyclerView
                rv.layoutManager = LinearLayoutManager(rv.context)
                rv.setItemAnimatorDisable()

                if (position == 0) {
                    rv.setup {
                        addType<DownloadItem>(R.layout.item_app_store_cells)
                        onBind {
                            val item = getModel<DownloadItem>()
                            val vb = getBinding<ItemAppStoreCellsBinding>()
                            bindItem(vb, item)
                        }
                        onPayload {
                            val payload = it.firstOrNull() as? String?
                            if (payload == "PROGRESS_UPDATE") {
                                val item = getModel<DownloadItem>()
                                val vb = getBinding<ItemAppStoreCellsBinding>()
                                bindButtonUI(vb, item.task, item, checkInstalled = false)
                            }
                        }
                    }.models = softwareList
                    softwareAdapter = rv.adapter as com.drake.brv.BindingAdapter
                } else {
                    rv.setup {
                        addType<DownloadItem>(R.layout.item_app_store_cells)
                        onBind {
                            val item = getModel<DownloadItem>()
                            val vb = getBinding<ItemAppStoreCellsBinding>()
                            bindItem(vb, item)
                        }
                        onPayload {
                            val payload = it.firstOrNull() as? String?
                            if (payload == "PROGRESS_UPDATE") {
                                val item = getModel<DownloadItem>()
                                val vb = getBinding<ItemAppStoreCellsBinding>()
                                bindButtonUI(vb, item.task, item, checkInstalled = false)
                            }
                        }
                    }.models = gameList
                    gameAdapter = rv.adapter as com.drake.brv.BindingAdapter
                }
            }

            override fun getItemCount(): Int = 2
        }

        com.google.android.material.tabs.TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = titles[position]
        }.attach()

        // Style the tabs
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
            }

            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }


    private fun loadData() {
        viewModel.loadAppListFromAssets()
        viewModel.loadGameListFromAssets()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.appList.collect { list ->
                // Check installed logic
                withContext(Dispatchers.IO) { checkInstalledStatus(list) }
                softwareList.clear()
                softwareList.addAll(list)
                softwareAdapter?.notifyDataSetChanged()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.gameList.collect { list ->
                withContext(Dispatchers.IO) { checkInstalledStatus(list) }
                gameList.clear()
                gameList.addAll(list)
                gameAdapter?.notifyDataSetChanged()
            }
        }
    }

    private fun checkInstalledStatus(list: List<DownloadItem>) {
        list.forEach { item ->
            val pkg = item.packageName.orEmpty()
            val storeVC = item.versionCode
            item.isInstalled = pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(requireContext(), pkg, storeVC)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bindItem(vb: ItemAppStoreCellsBinding, item: DownloadItem) {
        if (!item.icon.isNullOrEmpty()) {
            Glide.with(vb.ivIcon).load(item.icon).into(vb.ivIcon)
        } else {
            vb.ivIcon.setImageResource(R.color.purple_200)
        }

        vb.tvAppName.text = item.name
        vb.tvAppSize.text = "大小:" + formatFileSize(item.sizeBytes)
        vb.tvAppVersion.text = "版本:${item.version}"

        if (item.task == null) {
            val existingTask = DownloadManager.getTaskByUrl(item.url)
            if (existingTask != null) item.task = existingTask
        }

        bindButtonUI(vb, item.task, item, checkInstalled = true)

        vb.btnUpdate.fastClick {
            handleClick(item, vb)
        }

        vb.ivIcon.fastClick { openDetail(item) }
        vb.clRoot.fastClick { openDetail(item) }
    }

    private fun bindButtonUI(
        vb: ItemAppStoreCellsBinding, task: DownloadTask?, item: DownloadItem? = null, checkInstalled: Boolean = false
    ) {
        if (checkInstalled && item != null && item.isInstalled) {
            vb.btnUpdate.setText("打开")
            vb.btnUpdate.setProgress(100)
            vb.btnUpdate.isEnabled = true
            return
        }

        when (task?.status) {
            DownloadStatus.COMPLETED -> {
                val health = task.let { AppUtils.checkFileHealth(it) }
                if (health == AppUtils.FileHealth.OK) {
                    vb.btnUpdate.setText("安装")
                    vb.btnUpdate.setProgress(100)
                    vb.btnUpdate.isEnabled = true
                } else {
                    vb.btnUpdate.setText("下载")
                    vb.btnUpdate.setProgress(0)
                    vb.btnUpdate.isEnabled = true
                }
            }

            DownloadStatus.DOWNLOADING -> {
                vb.btnUpdate.setText("${task.progress}%")
                vb.btnUpdate.setProgress(task.progress)
                vb.btnUpdate.isEnabled = true
            }

            DownloadStatus.PAUSED -> {
                vb.btnUpdate.setText("继续")
                vb.btnUpdate.setProgress(task.progress)
                vb.btnUpdate.isEnabled = true
            }

            DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                vb.btnUpdate.setText("等待中")
                vb.btnUpdate.isEnabled = true
            }

            DownloadStatus.FAILED -> {
                vb.btnUpdate.setText("重试")
                vb.btnUpdate.isEnabled = true
            }

            else -> {
                vb.btnUpdate.setText("下载")
                vb.btnUpdate.setProgress(0)
                vb.btnUpdate.isEnabled = true
            }
        }
    }

    private fun handleClick(item: DownloadItem, vb: ItemAppStoreCellsBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            val pkg = item.packageName.orEmpty()
            val storeVC = item.versionCode
            val canOpen = withContext(Dispatchers.IO) {
                pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(requireContext(), pkg, storeVC)
            }
            if (canOpen) {
                if (!AppUtils.openApp(requireContext(), pkg)) {
                    ToastUtils.show("无法打开应用")
                }
                return@launch
            }

            val task = DownloadManager.getTaskByUrl(item.url)
            if (task != null) {
                item.task = task
            }

            when (task?.status) {
                DownloadStatus.DOWNLOADING -> {
                    DownloadManager.pauseTask(task.id, com.pichs.download.model.PauseReason.USER_MANUAL)
                    val pausedTask = task.copy(status = DownloadStatus.PAUSED)
                    updateItemTask(pausedTask)
                }

                DownloadStatus.PAUSED -> {
                    if (!com.pichs.download.utils.NetworkUtils.isNetworkAvailable(requireContext())) {
                        ToastUtils.show("网络不可用，请检查网络后重试")
                        return@launch
                    }
                    DownloadManager.resume(task.id)
                }

                DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                    DownloadManager.pauseTask(task.id, com.pichs.download.model.PauseReason.USER_MANUAL)
                    val pausedTask = task.copy(status = DownloadStatus.PAUSED)
                    updateItemTask(pausedTask)
                }

                DownloadStatus.COMPLETED -> {
                    val file = File(task.filePath, task.fileName)
                    if (file.exists()) {
                        openApk(task)
                    } else {
                        DownloadManager.deleteTask(task.id, deleteFile = false)
                        item.task = null
                        startDownload(item)
                    }
                }

                DownloadStatus.FAILED -> startDownload(item)
                null -> {
                    startDownload(item)
                }

                else -> {}
            }
        }
    }

    private fun startDownload(item: DownloadItem) {
        viewModel.requestDownload(item)
    }

    private fun openApk(task: DownloadTask) {
        val file = File(task.filePath, task.fileName)
        if (!file.exists()) return
        val uri: Uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        startActivity(intent)
    }

    private fun openDetail(item: DownloadItem) {
        val i = Intent(requireContext(), AppDetailActivity::class.java).apply {
            putExtra("app_info", item)
        }
        startActivity(i)
    }

    private fun bindFlowListener() {
        flowListener.bindToLifecycle(lifecycleOwner = this, onTaskProgress = { task, progress, speed ->
            if (isDetached) return@bindToLifecycle
            updateItemTaskWithProgress(task, progress, speed)
        }, onTaskComplete = { task, file ->
            if (isDetached) return@bindToLifecycle
            updateItemTask(task)
        }, onTaskError = { task, error ->
            if (isDetached) return@bindToLifecycle
            updateItemTask(task)
        }, onTaskPaused = { task ->
            if (isDetached) return@bindToLifecycle
            updateItemTask(task)
        }, onTaskResumed = { task ->
            if (isDetached) return@bindToLifecycle
            updateItemTask(task)
        }, onTaskCancelled = { task ->
            if (isDetached) return@bindToLifecycle
            updateItemTask(task)
        })
    }

    private fun updateItemTask(task: DownloadTask) {
        updateListItem(softwareList, softwareAdapter, task)
        updateListItem(gameList, gameAdapter, task)
    }

    private fun updateListItem(list: List<DownloadItem>, adapter: com.drake.brv.BindingAdapter?, task: DownloadTask) {
        val idx = list.indexOfFirst { it.url == task.url }
        if (idx >= 0) {
            (list as MutableList)[idx].task = task
            adapter?.notifyItemChanged(idx)
        }
    }

    private val lastProgressUpdateTimeMap = mutableMapOf<String, Long>()
    private val progressUpdateInterval = 300L

    private fun updateItemTaskWithProgress(task: DownloadTask, progress: Int, speed: Long) {
        if (progress >= 100 || task.status == DownloadStatus.COMPLETED) {
            lastProgressUpdateTimeMap.remove(task.id)
            updateListItemWithProgress(softwareList, softwareAdapter, task)
            updateListItemWithProgress(gameList, gameAdapter, task)
            return
        }

        val now = System.currentTimeMillis()
        val lastUpdateTime = lastProgressUpdateTimeMap[task.id] ?: 0L
        if (now - lastUpdateTime < progressUpdateInterval) {
            return
        }
        lastProgressUpdateTimeMap[task.id] = now

        updateListItemWithProgress(softwareList, softwareAdapter, task)
        updateListItemWithProgress(gameList, gameAdapter, task)
    }

    private fun updateListItemWithProgress(list: List<DownloadItem>, adapter: com.drake.brv.BindingAdapter?, task: DownloadTask) {
        val idx = list.indexOfFirst { it.url == task.url }
        if (idx >= 0) {
            (list as MutableList)[idx].task = task
            // Prevent Completed flickering
            if (list[idx].task?.status == DownloadStatus.COMPLETED && task.status != DownloadStatus.COMPLETED) {
                return
            }
            list[idx].task = task
            // Ensure on main thread
            binding.viewPager.post {
                adapter?.notifyItemChanged(idx, "PROGRESS_UPDATE")
            }
        }
    }
}
