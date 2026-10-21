package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import androidx.core.content.FileProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
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
    private val tasksByUrl = HashMap<String, DownloadTask>()
    private val cellsBinder = CellsBinder()

    // Track adapters for the two pages
    private var softwareAdapter: StoreAppAdapter? = null
    private var gameAdapter: StoreAppAdapter? = null

    override fun afterOnCreateView(rootView: View?) {
        initViewPager()
        loadData()
        observeTaskState()

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
                        if (!isVisible) return@collect
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.confirmBatchDownload(event.apps)
                        }
                        CellularConfirmDialog.show(event.totalSize, event.apps.size)
                    }

                    is UiEvent.ShowWifiOnlyDialog -> {
                        if (!isVisible) return@collect
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.startDownloadAndPause(event.apps)
                        }
                        CellularConfirmDialog.show(event.totalSize, event.apps.size, CellularConfirmDialog.MODE_WIFI_ONLY)
                    }

                    is UiEvent.ShowNoNetworkDialog -> {
                        if (!isVisible) return@collect
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

                val adapter = StoreAppAdapter(R.layout.item_app_store_cells, cellsBinder)
                rv.adapter = adapter
                if (position == 0) {
                    softwareAdapter = adapter
                    adapter.submitList(buildRows(softwareList))
                } else {
                    gameAdapter = adapter
                    adapter.submitList(buildRows(gameList))
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
                submitRows()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.gameList.collect { list ->
                withContext(Dispatchers.IO) { checkInstalledStatus(list) }
                gameList.clear()
                gameList.addAll(list)
                submitRows()
            }
        }
    }

    private fun observeTaskState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadManager.flowDownloadListener.observeAllTasks().collect { tasks ->
                    tasksByUrl.clear()
                    tasks.forEach { tasksByUrl[it.url] = it }
                    submitRows()
                }
            }
        }
    }

    private fun submitRows() {
        softwareAdapter?.submitList(buildRows(softwareList))
        gameAdapter?.submitList(buildRows(gameList))
    }

    private fun buildRows(list: List<DownloadItem>): List<StoreAppRow> {
        return list.map { item ->
            StoreAppRow(item, tasksByUrl[item.url], item.isInstalled)
        }
    }

    private inner class CellsBinder : StoreAppAdapter.Binder {
        override fun bind(view: View, row: StoreAppRow) {
            bindItem(ItemAppStoreCellsBinding.bind(view), row)
        }

        override fun bindProgress(view: View, row: StoreAppRow) {
            bindButtonUI(ItemAppStoreCellsBinding.bind(view), row.task, row.installed)
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
    private fun bindItem(vb: ItemAppStoreCellsBinding, row: StoreAppRow) {
        val item = row.item
        if (!item.icon.isNullOrEmpty()) {
            Glide.with(vb.ivIcon).load(item.icon).into(vb.ivIcon)
        } else {
            vb.ivIcon.setImageResource(R.color.purple_200)
        }

        vb.tvAppName.text = item.name
        vb.tvAppSize.text = "大小:" + formatFileSize(item.sizeBytes)
        vb.tvAppVersion.text = "版本:${item.version}"

        bindButtonUI(vb, row.task, row.installed)

        vb.btnUpdate.fastClick {
            handleClick(item)
        }

        vb.ivIcon.fastClick { openDetail(item) }
        vb.clRoot.fastClick { openDetail(item) }
    }

    private fun bindButtonUI(
        vb: ItemAppStoreCellsBinding, task: DownloadTask?, installed: Boolean = false
    ) {
        if (installed) {
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

    private fun handleClick(item: DownloadItem) {
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

            when (task?.status) {
                DownloadStatus.DOWNLOADING -> {
                    DownloadManager.pauseTask(task.id, com.pichs.download.model.PauseReason.USER_MANUAL)
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
                }

                DownloadStatus.COMPLETED -> {
                    val file = File(task.filePath, task.fileName)
                    if (file.exists()) {
                        openApk(task)
                    } else {
                        DownloadManager.deleteTask(task.id, deleteFile = false)
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

}
