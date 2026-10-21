package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.view.View
import androidx.core.content.FileProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.pichs.download.core.DownloadManager
import com.pichs.download.core.DownloadPriority
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.utils.DownloadLog
import com.pichs.download.demo.databinding.FragmentHomeBinding
import com.pichs.download.demo.databinding.ItemHorizontalAppBinding
import com.pichs.shanhai.base.base.BaseFragment
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.kotlinext.fastClick
import com.pichs.xbase.kotlinext.setItemAnimatorDisable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HomeFragment : BaseFragment<FragmentHomeBinding>() {

    // 三个分类列表
    private val urgentList = arrayListOf<DownloadItem>()   // 必装应用
    private val highList = arrayListOf<DownloadItem>()     // 推荐应用
    private val normalList = arrayListOf<DownloadItem>()   // 常用应用

    // ViewModel (Using activityViewModels to share if needed, or viewModels for local)
    // Keeping logic similar to Activity, but scoped to Fragment
    private val viewModel: MainViewModel by activityViewModels()

    private val tasksByUrl = HashMap<String, DownloadTask>()
    private val horizontalBinder = HorizontalBinder()
    private val urgentAdapter = StoreAppAdapter(R.layout.item_horizontal_app, horizontalBinder)
    private val highAdapter = StoreAppAdapter(R.layout.item_horizontal_app, horizontalBinder)
    private val normalAdapter = StoreAppAdapter(R.layout.item_horizontal_app, horizontalBinder)

    override fun afterOnCreateView(rootView: View?) {
        initListener()
        initRecyclerViews()
        initCategoryLists()
        observeTaskState()
    }

    private fun initListener() {

        binding.ivDownloadManager.setOnClickListener {
            startActivity(Intent(requireContext(), DownloadManagerActivity::class.java))
        }

        // 一键下载全部
        binding.btnDownloadAll.fastClick {
            downloadAllWithPriority()
        }

        // 订阅 ViewModel 的 UI 事件 (ViewModel scope is Activity, so events might be shared)
        // Note: In Fragment, we observe viewLifecycleOwner.lifecycleScope
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiEvent.collect { event ->
                when (event) {
                    is UiEvent.ShowToast -> ToastUtils.show(event.message)
                    is UiEvent.ShowCellularConfirmDialog -> {
                        if (!isVisible) return@collect
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.confirmBatchDownload(event.apps)
                        }
                        CellularConfirmDialog.show(
                            totalSize = event.totalSize,
                            taskCount = event.apps.size,
                            mode = CellularConfirmDialog.MODE_CELLULAR
                        )
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
    }

    private fun initCategoryLists() {
        // ViewModel might have already loaded? call load just in case or observe
        viewModel.loadAppListFromAssets()
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.appList.collect { appList ->
                withContext(Dispatchers.IO) {
                    appList.forEach { item ->
                        val pkg = item.packageName.orEmpty()
                        val storeVC = item.versionCode
                        item.isInstalled = pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(requireContext(), pkg, storeVC)
                    }
                }

                urgentList.clear()
                highList.clear()
                normalList.clear()

                appList.forEachIndexed { index, item ->
                    when {
                        index < 5 -> {
                            item.priority = DownloadPriority.URGENT.value
                            urgentList.add(item)
                        }

                        index < 15 -> {
                            item.priority = DownloadPriority.HIGH.value
                            highList.add(item)
                        }

                        else -> {
                            item.priority = DownloadPriority.NORMAL.value
                            normalList.add(item)
                        }
                    }
                }

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
        urgentAdapter.submitList(buildRows(urgentList))
        highAdapter.submitList(buildRows(highList))
        normalAdapter.submitList(buildRows(normalList))
    }

    private fun buildRows(list: List<DownloadItem>): List<StoreAppRow> {
        return list.map { item ->
            StoreAppRow(item, tasksByUrl[item.url], item.isInstalled)
        }
    }

    private fun initRecyclerViews() {
        binding.rvUrgent.setItemAnimatorDisable()
        binding.rvUrgent.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvUrgent.adapter = urgentAdapter

        binding.rvHigh.setItemAnimatorDisable()
        binding.rvHigh.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvHigh.adapter = highAdapter

        binding.rvNormal.setItemAnimatorDisable()
        binding.rvNormal.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvNormal.adapter = normalAdapter
    }

    private inner class HorizontalBinder : StoreAppAdapter.Binder {
        override fun bind(view: View, row: StoreAppRow) {
            bindHorizontalItem(ItemHorizontalAppBinding.bind(view), row)
        }

        override fun bindProgress(view: View, row: StoreAppRow) {
            bindButtonUI(ItemHorizontalAppBinding.bind(view), row.task, row.installed)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun bindHorizontalItem(vb: ItemHorizontalAppBinding, row: StoreAppRow) {
        val item = row.item
        if (!item.icon.isNullOrEmpty()) {
            Glide.with(vb.ivCover).load(item.icon).into(vb.ivCover)
        } else {
            vb.ivCover.setImageResource(R.color.purple_200)
        }

        vb.tvAppName.text = item.name

        when (item.priority) {
            DownloadPriority.URGENT.value -> {
                vb.tvPriority.text = "必装"
                vb.tvPriority.setNormalBackgroundColor(Color.parseColor("#FF5722"))
            }

            DownloadPriority.HIGH.value -> {
                vb.tvPriority.text = "推荐"
                vb.tvPriority.setNormalBackgroundColor(Color.parseColor("#2196F3"))
            }

            else -> {
                vb.tvPriority.text = "常用"
                vb.tvPriority.setNormalBackgroundColor(Color.parseColor("#607D8B"))
            }
        }

        bindButtonUI(vb, row.task, row.installed)

        vb.btnDownload.fastClick { handleClick(item) }
        vb.ivCover.fastClick { openDetail(item) }
    }

    private fun bindButtonUI(
        vb: ItemHorizontalAppBinding, task: DownloadTask?, installed: Boolean = false
    ) {
        if (installed) {
            vb.btnDownload.setText("打开")
            vb.btnDownload.setProgress(100)
            vb.btnDownload.isEnabled = true
            return
        }

        when (task?.status) {
            DownloadStatus.COMPLETED -> {
                val health = task.let { AppUtils.checkFileHealth(it) }
                if (health == AppUtils.FileHealth.OK) {
                    vb.btnDownload.setText("安装")
                    vb.btnDownload.setProgress(100)
                    vb.btnDownload.isEnabled = true
                } else {
                    vb.btnDownload.setText("下载")
                    vb.btnDownload.setProgress(0)
                    vb.btnDownload.isEnabled = true
                }
            }

            DownloadStatus.DOWNLOADING -> {
                vb.btnDownload.setText("${task.progress}%")
                vb.btnDownload.setProgress(task.progress)
                vb.btnDownload.isEnabled = true
            }

            DownloadStatus.PAUSED -> {
                vb.btnDownload.setText("继续")
                vb.btnDownload.setProgress(task.progress)
                vb.btnDownload.isEnabled = true
            }

            DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                vb.btnDownload.setText("等待中")
                vb.btnDownload.isEnabled = true
            }

            DownloadStatus.FAILED -> {
                vb.btnDownload.setText("重试")
                vb.btnDownload.isEnabled = true
            }

            else -> {
                vb.btnDownload.setText("下载")
                vb.btnDownload.setProgress(0)
                vb.btnDownload.isEnabled = true
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

    private fun downloadAllWithPriority() {
        ToastUtils.show("正在检查应用状态...")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val filteredNormal = normalList.filter { !isUpToDate(it) }
            val filteredHigh = highList.filter { !isUpToDate(it) }
            val filteredUrgent = urgentList.filter { !isUpToDate(it) }

            val allApps = filteredNormal + filteredHigh + filteredUrgent

            if (allApps.isEmpty()) {
                withContext(Dispatchers.Main) {
                    ToastUtils.show("所有应用都已是最新版本")
                }
                return@launch
            }

            val tasksToResume = mutableListOf<DownloadTask>()
            val itemsToCreate = mutableListOf<DownloadItem>()
            var totalSize = 0L

            for (item in allApps) {
                val existingTask = DownloadManager.getTaskByUrl(item.url)

                if (existingTask == null) {
                    itemsToCreate.add(item)
                    totalSize += item.sizeBytes
                } else {
                    when (existingTask.status) {
                        DownloadStatus.DOWNLOADING, DownloadStatus.WAITING, DownloadStatus.PENDING -> {}
                        DownloadStatus.PAUSED -> {
                            tasksToResume.add(existingTask)
                            val effective = if (existingTask.totalSize > 0) {
                                (existingTask.totalSize - existingTask.currentSize).coerceAtLeast(0)
                            } else {
                                item.sizeBytes
                            }
                            totalSize += effective
                        }

                        DownloadStatus.COMPLETED -> {
                            val file = File(existingTask.filePath, existingTask.fileName)
                            if (!file.exists()) {
                                DownloadManager.deleteTask(existingTask.id, false)
                                itemsToCreate.add(item)
                                totalSize += item.sizeBytes
                            }
                        }

                        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                            DownloadManager.deleteTask(existingTask.id, false)
                            itemsToCreate.add(item)
                            totalSize += item.sizeBytes
                        }
                    }
                }
            }

            if (tasksToResume.isEmpty() && itemsToCreate.isEmpty()) {
                withContext(Dispatchers.Main) {
                    ToastUtils.show("任务都在下载队列中")
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                val isWifi = com.pichs.download.utils.NetworkUtils.isWifiAvailable(requireContext())
                val isCellular = com.pichs.download.utils.NetworkUtils.isCellularAvailable(requireContext())

                when {
                    isWifi -> {
                        executeBatchDownload(tasksToResume, itemsToCreate, cellularConfirmed = false)
                    }

                    isCellular -> {
                        CellularConfirmDialog.show(
                            totalSize = totalSize,
                            taskCount = tasksToResume.size + itemsToCreate.size,
                            mode = CellularConfirmDialog.MODE_CELLULAR,
                            onConfirm = { useCellular ->
                                if (useCellular) {
                                    executeBatchDownload(tasksToResume, itemsToCreate, cellularConfirmed = true)
                                } else {
                                    ToastUtils.show("请连接WiFi后重试")
                                }
                            })
                    }

                    else -> {
                        CellularConfirmDialog.show(
                            totalSize = totalSize,
                            taskCount = tasksToResume.size + itemsToCreate.size,
                            mode = CellularConfirmDialog.MODE_NO_NETWORK,
                            onConfirm = { a ->
                                executeBatchDownload(tasksToResume, itemsToCreate, cellularConfirmed = false)
                            })
                    }
                }
            }
        }
    }

    private fun executeBatchDownload(
        resumeList: List<DownloadTask>, createList: List<DownloadItem>, cellularConfirmed: Boolean
    ) {
        if (resumeList.isNotEmpty()) {
            resumeList.forEach { task ->
                if (cellularConfirmed) {
                    DownloadManager.updateTaskCellularConfirmed(task.id, true)
                }
                DownloadManager.resume(task.id)
            }
        }

        if (createList.isNotEmpty()) {
            val builders = createList.map { item ->
                val meta = ExtraMeta(
                    name = item.name, packageName = item.packageName.orEmpty(), versionCode = item.versionCode, icon = item.icon
                )

                val priority = when (item.priority) {
                    DownloadPriority.URGENT.value -> DownloadPriority.URGENT
                    DownloadPriority.HIGH.value -> DownloadPriority.HIGH
                    DownloadPriority.LOW.value -> DownloadPriority.LOW
                    else -> DownloadPriority.NORMAL
                }

                DownloadManager.downloadWithPriority(item.url, priority).fileName(item.name.replace(" ", "_") + ".apk").estimatedSize(item.sizeBytes)
                    .extras(meta.toJson()).cellularConfirmed(cellularConfirmed)
            }

            val tasks = DownloadManager.startTasks(builders)

            DownloadLog.d("HomeFragment", "批量新建了 ${tasks.size} 个任务")
        }
    }

    private fun isUpToDate(item: DownloadItem): Boolean {
        val pkg = item.packageName.orEmpty()
        val storeVC = item.versionCode
        return pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(requireContext(), pkg, storeVC)
    }

    private fun openDetail(item: DownloadItem) {
        val i = Intent(requireContext(), AppDetailActivity::class.java).apply {
            putExtra("app_info", item)
        }
        startActivity(i)
    }

}
