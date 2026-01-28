package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.view.View
import androidx.core.content.FileProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.drake.brv.utils.setup
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

    // Flow监听器
    private val flowListener = DownloadManager.flowListener


    override fun afterOnCreateView(rootView: View?) {
        initListener()
        initCategoryLists()
        initRecyclerViews()
        bindFlowListener()
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
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.confirmBatchDownload(event.apps)
                        }
                        CellularConfirmDialog.show(
                            totalSize = event.totalSize,
                            taskCount = event.apps.size,
                            mode = CellularConfirmDialog.MODE_CELLULAR,
                            onConfirm = {

                            }, onCancel = {

                            })
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

                binding.rvUrgent.adapter?.notifyDataSetChanged()
                binding.rvHigh.adapter?.notifyDataSetChanged()
                binding.rvNormal.adapter?.notifyDataSetChanged()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initRecyclerViews() {
        binding.rvUrgent.setItemAnimatorDisable()
        binding.rvUrgent.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvUrgent.setup {
            addType<DownloadItem>(R.layout.item_horizontal_app)
            onBind {
                val item = getModel<DownloadItem>()
                val vb = getBinding<ItemHorizontalAppBinding>()
                bindHorizontalItem(vb, item, DownloadPriority.URGENT.value)
            }
            onPayload {
                val payload = it.firstOrNull() as? String?
                if (payload == "PROGRESS_UPDATE") {
                    val item = getModel<DownloadItem>()
                    val vb = getBinding<ItemHorizontalAppBinding>()
                    bindButtonUI(vb, item.task, item, checkInstalled = false)
                }
            }
        }.models = urgentList

        binding.rvHigh.setItemAnimatorDisable()
        binding.rvHigh.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvHigh.setup {
            addType<DownloadItem>(R.layout.item_horizontal_app)
            onBind {
                val item = getModel<DownloadItem>()
                val vb = getBinding<ItemHorizontalAppBinding>()
                bindHorizontalItem(vb, item, DownloadPriority.HIGH.value)
            }
            onPayload {
                val payload = it.firstOrNull() as? String?
                if (payload == "PROGRESS_UPDATE") {
                    val item = getModel<DownloadItem>()
                    val vb = getBinding<ItemHorizontalAppBinding>()
                    bindButtonUI(vb, item.task, item, checkInstalled = false)
                }
            }
        }.models = highList

        binding.rvNormal.setItemAnimatorDisable()
        binding.rvNormal.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvNormal.setup {
            addType<DownloadItem>(R.layout.item_horizontal_app)
            onBind {
                val item = getModel<DownloadItem>()
                val vb = getBinding<ItemHorizontalAppBinding>()
                bindHorizontalItem(vb, item, DownloadPriority.NORMAL.value)
            }
            onPayload {
                val payload = it.firstOrNull() as? String?
                if (payload == "PROGRESS_UPDATE") {
                    val item = getModel<DownloadItem>()
                    val vb = getBinding<ItemHorizontalAppBinding>()
                    bindButtonUI(vb, item.task, item, checkInstalled = false)
                }
            }
        }.models = normalList
    }

    @SuppressLint("SetTextI18n")
    private fun bindHorizontalItem(vb: ItemHorizontalAppBinding, item: DownloadItem, priority: Int) {
        if (!item.icon.isNullOrEmpty()) {
            Glide.with(vb.ivCover).load(item.icon).into(vb.ivCover)
        } else {
            vb.ivCover.setImageResource(R.color.purple_200)
        }

        vb.tvAppName.text = item.name

        when (priority) {
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

        if (item.task == null) {
            val existingTask = DownloadManager.getTaskByUrl(item.url)
            if (existingTask != null) item.task = existingTask
        }

        bindButtonUI(vb, item.task, item, checkInstalled = true)

        vb.btnDownload.fastClick { handleClick(item, vb) }
        vb.ivCover.fastClick { openDetail(item) }
    }

    private fun bindButtonUI(
        vb: ItemHorizontalAppBinding, task: DownloadTask?, item: DownloadItem? = null, checkInstalled: Boolean = false
    ) {
        if (checkInstalled && item != null && item.isInstalled) {
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

    private fun handleClick(item: DownloadItem, vb: ItemHorizontalAppBinding) {
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
                        startDownload(item, vb)
                    }
                }

                DownloadStatus.FAILED -> startDownload(item, vb)
                null -> {
                    startDownload(item, vb)
                }

                else -> {}
            }
        }
    }

    private fun startDownload(item: DownloadItem, vb: ItemHorizontalAppBinding) {
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

            tasks.forEach { task ->
                val item = createList.find { it.url == task.url }
                if (item != null) item.task = task
            }

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
        updateListItem(urgentList, binding.rvUrgent, task)
        updateListItem(highList, binding.rvHigh, task)
        updateListItem(normalList, binding.rvNormal, task)
    }

    private fun updateListItem(list: List<DownloadItem>, rv: androidx.recyclerview.widget.RecyclerView, task: DownloadTask) {
        val idx = list.indexOfFirst { it.url == task.url }
        if (idx >= 0) {
            (list as MutableList)[idx].task = task
            rv.adapter?.notifyItemChanged(idx)
        }
    }

    private val lastProgressUpdateTimeMap = mutableMapOf<String, Long>()
    private val progressUpdateInterval = 300L

    private fun updateItemTaskWithProgress(task: DownloadTask, progress: Int, speed: Long) {
        if (progress >= 100 || task.status == DownloadStatus.COMPLETED) {
            lastProgressUpdateTimeMap.remove(task.id)
            updateListItemWithProgress(urgentList, binding.rvUrgent, task)
            updateListItemWithProgress(highList, binding.rvHigh, task)
            updateListItemWithProgress(normalList, binding.rvNormal, task)
            return
        }

        val now = System.currentTimeMillis()
        val lastUpdateTime = lastProgressUpdateTimeMap[task.id] ?: 0L
        if (now - lastUpdateTime < progressUpdateInterval) {
            return
        }
        lastProgressUpdateTimeMap[task.id] = now

        updateListItemWithProgress(urgentList, binding.rvUrgent, task)
        updateListItemWithProgress(highList, binding.rvHigh, task)
        updateListItemWithProgress(normalList, binding.rvNormal, task)
    }

    private fun updateListItemWithProgress(list: List<DownloadItem>, rv: androidx.recyclerview.widget.RecyclerView, task: DownloadTask) {
        val idx = list.indexOfFirst { it.url == task.url }
        if (idx >= 0) {
            val currentItem = (list as MutableList)[idx]
            if (currentItem.task?.status == DownloadStatus.COMPLETED && task.status != DownloadStatus.COMPLETED) {
                return
            }

            currentItem.task = task
            rv.post {
                rv.adapter?.notifyItemChanged(idx, "PROGRESS_UPDATE")
            }
        }
    }

}
