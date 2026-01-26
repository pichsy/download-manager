package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.drake.brv.utils.setup
import com.pichs.download.core.DownloadManager
import com.pichs.download.core.DownloadPriority
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.pichs.download.demo.databinding.ActivityMainBinding
import com.pichs.download.demo.databinding.ItemHorizontalAppBinding
import com.pichs.download.utils.DownloadLog
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.receiver.NetworkMonitor
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.kotlinext.fastClick
import com.pichs.xbase.kotlinext.setItemAnimatorDisable
import java.io.File
import android.provider.Settings
import android.graphics.Color
import com.pichs.download.demo.floatwindow.FloatBallView
import com.pichs.shanhai.base.receiver.InstallBroadcastReceiver
import kotlin.jvm.java

class MainActivity : BaseActivity<ActivityMainBinding>() {

    // 三个分类列表
    private val urgentList = arrayListOf<DownloadItem>()   // 必装应用 (priority=3)
    private val highList = arrayListOf<DownloadItem>()     // 推荐应用 (priority=2)
    private val normalList = arrayListOf<DownloadItem>()   // 常用应用 (priority=1)

    // ViewModel
    private val viewModel: MainViewModel by viewModels()

    // Flow监听器
    private val flowListener = DownloadManager.flowListener

    // 悬浮球
    private var floatBallView: FloatBallView? = null

    // 统一的名称归一化工具
    private fun normalizeName(n: String): String = n.substringBeforeLast('.').lowercase()
    val GRANT_PERMISSIONS = "com.gankao.dpc.request.GRANT_PERMISSIONS"
    val REMOVE_PERMISSIONS = "com.gankao.dpc.request.REMOVE_PERMISSIONS"

    override fun afterOnCreate() {
        NetworkMonitor(onNetworkChanged = { isWifi ->
            DownloadLog.d("网络类型变化，isWifi=$isWifi")
            if (isWifi) {
                ToastUtils.show("WIFI已连接")
            } else {
                ToastUtils.show("数据流量已连接")
            }
            // 统一调用 onNetworkRestored，内部会判断当前网络类型并做对应处理
            DownloadManager.onNetworkRestored()
        }, onNetworkLost = {
            DownloadLog.d("网络断开")
            ToastUtils.show("网络已断开")
            // 网络断开时不需要调用任何方法
            // 正在下载的任务会因为网络失败而自动暂停
        }).register(this)

        binding.tvTitle.fastClick {}

        binding.ivDownloadSettings.fastClick {
            startActivity(Intent(this, CellularSettingsActivity::class.java))
        }

        if (Settings.canDrawOverlays(this@MainActivity)) {
            showFloatBall()
            sendBroadcast(Intent(REMOVE_PERMISSIONS).apply {
                putExtra("packageName", packageName)
            })
        } else {
//            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
//            intent.data = Uri.parse("package:$packageName")
//            startActivity(intent)
        }


        InstallBroadcastReceiver().register(this)

//            lifecycleScope.launch {
//                sendBroadcast(Intent(GRANT_PERMISSIONS).apply {
//                    putExtra("packageName", packageName)
//                })

//                delay(5000)

//                requestPermissions(
//                    arrayOf(
//                        android.Manifest.permission.ACCESS_FINE_LOCATION,
//                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
//                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
//                        android.Manifest.permission.READ_PHONE_STATE,
//                        android.Manifest.permission.READ_PHONE_NUMBERS,
//                        android.Manifest.permission.CALL_PHONE,
//                        android.Manifest.permission.ANSWER_PHONE_CALLS,
//                        android.Manifest.permission.READ_CALL_LOG,
//                        android.Manifest.permission.WRITE_CALL_LOG,
//                        android.Manifest.permission.READ_SMS,
//                        android.Manifest.permission.SEND_SMS,
//                        android.Manifest.permission.RECEIVE_SMS,
//                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
//                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
//                        android.Manifest.permission.READ_MEDIA_IMAGES,
//                        android.Manifest.permission.READ_MEDIA_VIDEO,
//                        android.Manifest.permission.READ_MEDIA_AUDIO,
//                        android.Manifest.permission.CAMERA,
//                        android.Manifest.permission.RECORD_AUDIO,
//                        android.Manifest.permission.READ_CONTACTS,
//                        android.Manifest.permission.WRITE_CONTACTS,
//                        android.Manifest.permission.READ_CALENDAR,
//                        android.Manifest.permission.WRITE_CALENDAR,
//                        android.Manifest.permission.BLUETOOTH_CONNECT,
//                        android.Manifest.permission.BLUETOOTH_SCAN,
//                        android.Manifest.permission.BLUETOOTH_ADVERTISE,
//                        android.Manifest.permission.NEARBY_WIFI_DEVICES,
//                        android.Manifest.permission.POST_NOTIFICATIONS,
//                        android.Manifest.permission.SYSTEM_ALERT_WINDOW,
//                        "com.android.permission.GET_INSTALLED_APPS",
//                    ), 1002
//                )

//                if (Settings.canDrawOverlays(this@MainActivity)) {
//                    showFloatBall()
//                    delay(3000)
//                    sendBroadcast(Intent(REMOVE_PERMISSIONS).apply {
//                        putExtra("packageName", packageName)
//                    })
//                } else {
//                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
//                    intent.data = Uri.parse("package:$packageName")
//                    startActivity(intent)
//                }
//            }

        initListener()
        initCategoryLists()
        initRecyclerViews()
        bindFlowListener()
    }

    /**
     * 初始化分类列表数据
     * 必装应用：前5个，priority=3 (URGENT)
     * 推荐应用：6-15，priority=2 (HIGH)
     * 常用应用：剩余，priority=1 (NORMAL)
     */
    private fun initCategoryLists() {
        viewModel.loadAppListFromAssets()
        lifecycleScope.launch {
            viewModel.appList.collect { appList ->
                // 在后台线程预计算安装状态
                withContext(Dispatchers.IO) {
                    appList.forEach { item ->
                        val pkg = item.packageName.orEmpty()
                        val storeVC = item.versionCode
                        item.isInstalled = pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(this@MainActivity, pkg, storeVC)
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


    private fun initListener() {
        DownloadLog.d("MainActivity", "======> initListener() 开始执行")

        binding.ivDownloadManager.setOnClickListener {
            startActivity(Intent(this, DownloadManagerActivity::class.java))
        }

        // 一键下载全部
        binding.btnDownloadAll.fastClick {
            downloadAllWithPriority()
        }

        // 订阅 ViewModel 的 UI 事件
        lifecycleScope.launch {
            viewModel.uiEvent.collect { event ->
                when (event) {
                    is UiEvent.ShowToast -> ToastUtils.show(event.message)
                    is UiEvent.ShowCellularConfirmDialog -> {
//                        CellularConfirmViewModel.pendingAction = {
//                            viewModel.confirmBatchDownload(event.apps)
//                        }
//                        CellularConfirmDialog.show(event.totalSize, event.apps.size)
                    }

                    is UiEvent.ShowWifiOnlyDialog -> {
//                        CellularConfirmViewModel.pendingAction = {
//                            viewModel.startDownloadAndPause(event.apps)
//                        }
//                        CellularConfirmDialog.show(event.totalSize, event.apps.size, CellularConfirmDialog.MODE_WIFI_ONLY)
                    }

                    is UiEvent.ShowNoNetworkDialog -> {
//                        CellularConfirmViewModel.pendingAction = {
//                            viewModel.startDownloadAndPauseForNetwork(event.apps)
//                        }
//                        CellularConfirmDialog.show(event.totalSize, event.apps.size, CellularConfirmDialog.MODE_NO_NETWORK)
                    }
                }
            }
        }

//        DownloadManager.setCheckAfterCallback(MyCheckAfterCallback(this))
        // 回调设置完成后，恢复中断的任务（僵尸任务 + 非用户手动暂停的任务）
        DownloadManager.restoreInterruptedTasks()
    }

    /**
     * 一键下载全部（按不同优先级）
     * 在后台线程执行，避免主线程 ANR
     * 过滤掉已安装且是最新版本的应用
     */
    /**
     * 一键下载全部（优化的智能逻辑）
     * 1. 过滤已安装.
     * 2. 分类：已在队列的不动，暂停的恢复，不存在/失败的创建.
     * 3. 网络检查：流量弹窗，无网弹窗.
     */
    private fun downloadAllWithPriority() {
        ToastUtils.show("正在检查应用状态...")

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. 过滤已安装
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

            // 2. 分类处理 (Resume vs Create)
            val tasksToResume = mutableListOf<DownloadTask>()
            val itemsToCreate = mutableListOf<DownloadItem>()
            var totalSize = 0L

            for (item in allApps) {
                // 始终获取最新状态
                val existingTask = DownloadManager.getTaskByUrl(item.url)

                if (existingTask == null) {
                    itemsToCreate.add(item)
                    totalSize += item.size
                } else {
                    when (existingTask.status) {
                        DownloadStatus.DOWNLOADING, DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                            // 已经在队列中，忽略
                        }

                        DownloadStatus.PAUSED -> {
                            tasksToResume.add(existingTask)
                            // 计算剩余大小
                            val effective = if (existingTask.totalSize > 0) {
                                (existingTask.totalSize - existingTask.currentSize).coerceAtLeast(0)
                            } else {
                                item.size
                            }
                            totalSize += effective
                        }

                        DownloadStatus.COMPLETED -> {
                            val file = File(existingTask.filePath, existingTask.fileName)
                            if (!file.exists()) {
                                // 文件丢失，重新下载
                                DownloadManager.deleteTask(existingTask.id, false)
                                itemsToCreate.add(item)
                                totalSize += item.size
                            }
                        }

                        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                            // 失败或取消，重新下载
                            DownloadManager.deleteTask(existingTask.id, false)
                            itemsToCreate.add(item)
                            totalSize += item.size
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

            // 3. 网络检查与执行
            withContext(Dispatchers.Main) {
                val isWifi = com.pichs.download.utils.NetworkUtils.isWifiAvailable(this@MainActivity)
                val isCellular = com.pichs.download.utils.NetworkUtils.isCellularAvailable(this@MainActivity)
                val count = tasksToResume.size + itemsToCreate.size

                when {
                    isWifi -> {
                        // WiFi 直接开干
                        executeBatchDownload(tasksToResume, itemsToCreate, cellularConfirmed = false)
                    }

                    isCellular -> {
                        // 流量弹窗
                        CellularConfirmDialog.show(
                            totalSize = totalSize, taskCount = count, mode = CellularConfirmDialog.MODE_CELLULAR, onConfirm = { useCellular ->
                                if (useCellular) {
                                    // 用户同意使用流量
                                    executeBatchDownload(tasksToResume, itemsToCreate, cellularConfirmed = true)
                                } else {
                                    // 用户选择去连WiFi -> 暂不操作，等待用户连网后再次点击
                                    ToastUtils.show("请连接WiFi后重试")
                                }
                            })
                    }

                    else -> {
                        // 无网络弹窗
                        CellularConfirmDialog.show(
                            totalSize = totalSize, taskCount = count, mode = CellularConfirmDialog.MODE_NO_NETWORK, onConfirm = { a ->
                                // 用户选择"等待网络" -> 创建并暂停
                                executeBatchDownload(tasksToResume, itemsToCreate, cellularConfirmed = false)
                            })
                    }
                }
            }
        }
    }

    /**
     * 执行批量下载操作
     */
    private fun executeBatchDownload(
        resumeList: List<DownloadTask>, createList: List<DownloadItem>, cellularConfirmed: Boolean
    ) {
        // 1. 恢复任务
        if (resumeList.isNotEmpty()) {
            resumeList.forEach { task ->
                if (cellularConfirmed) {
                    DownloadManager.updateTaskCellularConfirmed(task.id, true)
                }
                DownloadManager.resume(task.id)
            }
        }

        // 2. 创建新任务
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

                DownloadManager.downloadWithPriority(item.url, priority).fileName(item.name.replace(" ", "_") + ".apk").estimatedSize(item.size)
                    .extras(meta.toJson()).cellularConfirmed(cellularConfirmed) // 预设流量确认标记
            }

            val tasks = DownloadManager.startTasks(builders)

            // 更新 item 绑定
            tasks.forEach { task ->
                val item = createList.find { it.url == task.url }
                if (item != null) item.task = task
            }

            DownloadLog.d("MainActivity", "批量新建了 ${tasks.size} 个任务")
        }

        val total = resumeList.size + createList.size
        // ToastUtils.show("已开始下载 $total 个应用") 
    }

    /**
     * 检查应用是否已安装且是最新版本
     */
    private fun isUpToDate(item: DownloadItem): Boolean {
        val pkg = item.packageName.orEmpty()
        val storeVC = item.versionCode
        return pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(this, pkg, storeVC)
    }

    @SuppressLint("SetTextI18n")
    private fun initRecyclerViews() {
        // 必装应用列表（横向）
        binding.rvUrgent.setItemAnimatorDisable()
        binding.rvUrgent.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
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
                    // ✅ 只更新进度相关的UI，不重新加载图片等
                    bindButtonUI(vb, item.task, item, checkInstalled = false)
                }
            }
        }.models = urgentList

        // 推荐应用列表（横向）
        binding.rvHigh.setItemAnimatorDisable()
        binding.rvHigh.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
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
                    // ✅ 只更新进度相关的UI，不重新加载图片等
                    bindButtonUI(vb, item.task, item, checkInstalled = false)
                }
            }
        }.models = highList

        // 常用应用列表（横向）
        binding.rvNormal.setItemAnimatorDisable()
        binding.rvNormal.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
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
                    // ✅ 只更新进度相关的UI，不重新加载图片等
                    bindButtonUI(vb, item.task, item, checkInstalled = false)
                }
            }
        }.models = normalList
    }

    /**
     * 绑定横向列表项（复用现有逻辑）
     */
    @SuppressLint("SetTextI18n")
    private fun bindHorizontalItem(vb: ItemHorizontalAppBinding, item: DownloadItem, priority: Int) {
        // 图标
        if (!item.icon.isNullOrEmpty()) {
            Glide.with(vb.ivCover).load(item.icon).into(vb.ivCover)
        } else {
            vb.ivCover.setImageResource(R.color.purple_200)
        }

        // 应用名
        vb.tvAppName.text = item.name

        // 优先级标签
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

        // 关联已有任务
        if (item.task == null) {
            val existingTask = DownloadManager.getTaskByUrl(item.url)
            if (existingTask != null) item.task = existingTask
        }

        // 绑定按钮状态（复用现有逻辑）
        bindButtonUI(vb, item.task, item, checkInstalled = true)

        // 点击事件
        vb.btnDownload.fastClick { handleClick(item, vb) }
        vb.ivCover.fastClick { openDetail(item) }
    }

    /**
     * 统一的按钮状态绑定方法（复用现有逻辑）
     */
    private fun bindButtonUI(
        vb: ItemHorizontalAppBinding, task: DownloadTask?, item: DownloadItem? = null, checkInstalled: Boolean = false
    ) {
        // 使用缓存的安装状态，避免主线程查询 PackageManager
        if (checkInstalled && item != null && item.isInstalled) {
            vb.btnDownload.setText("打开")
            vb.btnDownload.setProgress(100)
            vb.btnDownload.isEnabled = true
            return
        }

        // 根据任务状态绑定按钮
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
        lifecycleScope.launch {
            // 在后台线程检查应用是否已安装且是最新版本
            val pkg = item.packageName.orEmpty()
            val storeVC = item.versionCode
            val canOpen = withContext(Dispatchers.IO) {
                pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(this@MainActivity, pkg, storeVC)
            }
            if (canOpen) {
                if (!AppUtils.openApp(this@MainActivity, pkg)) {
                    ToastUtils.show("无法打开应用")
                }
                return@launch
            }
            // ✅ 始终获取最新任务状态，避免使用过期缓存
            val task = DownloadManager.getTaskByUrl(item.url)
            if (task != null) {
                item.task = task  // ✅ 同步缓存
            }
            when (task?.status) {
                DownloadStatus.DOWNLOADING -> {
                    // 暂停任务
                    DownloadManager.pauseTask(task.id, com.pichs.download.model.PauseReason.USER_MANUAL)
                    // ✅ 乐观更新：立即更新UI为暂停状态
                    val pausedTask = task.copy(status = DownloadStatus.PAUSED)
                    updateItemTask(pausedTask)
                }

                DownloadStatus.PAUSED -> {
                    if (!com.pichs.download.utils.NetworkUtils.isNetworkAvailable(this@MainActivity)) {
                        ToastUtils.show("网络不可用，请检查网络后重试")
                        return@launch
                    }
                    // 直接调用resume，不要乐观更新UI
                    // Flow监听器会在任务实际状态变化时自动更新UI
                    DownloadManager.resume(task.id)
                }

                DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                    // 暂停任务
                    DownloadManager.pauseTask(task.id, com.pichs.download.model.PauseReason.USER_MANUAL)
                    // ✅ 乐观更新：立即更新UI为暂停状态
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
                    // 没有任务，开始新下载
                    startDownload(item, vb)
                }

                else -> {

                }
            }
        }
    }

    private fun startDownload(item: DownloadItem, vb: ItemHorizontalAppBinding) {
        viewModel.requestDownload(item)
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

    // 绑定Flow监听，保持列表项与任务状态同步
    private fun bindFlowListener() {
        flowListener.bindToLifecycle(lifecycleOwner = this, onTaskProgress = { task, progress, speed ->
            if (isDestroyed) return@bindToLifecycle
            DownloadLog.d("MainActivity", "✅ MainActivity 收到完成回调-onTaskProgress: ${task.fileName}，task=${task.status}, progress=${progress}")
            updateItemTaskWithProgress(task, progress, speed)
            updateFloatBallProgress(task, progress, speed)
        }, onTaskComplete = { task, file ->
            DownloadLog.d("MainActivity", "✅ MainActivity 收到完成回调: ${task.fileName}")
            if (isDestroyed) return@bindToLifecycle
            updateItemTask(task)
        }, onTaskError = { task, error ->
            if (isDestroyed) return@bindToLifecycle
            updateItemTask(task)
        }, onTaskPaused = { task ->
            if (isDestroyed) return@bindToLifecycle
            updateItemTask(task)
        }, onTaskResumed = { task ->
            if (isDestroyed) return@bindToLifecycle
            updateItemTask(task)
        }, onTaskCancelled = { task ->
            if (isDestroyed) return@bindToLifecycle
            updateItemTask(task)
        })
    }

    // 根据任务匹配列表项并刷新
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

    // 专门处理进度更新的方法（添加防抖机制）
    private val lastProgressUpdateTimeMap = mutableMapOf<String, Long>()
    private val progressUpdateInterval = 300L

    private fun updateItemTaskWithProgress(task: DownloadTask, progress: Int, speed: Long) {
        // 关键修复：当进度达到100或任务完成时，必须强制刷新，忽略防抖
        // 之前的逻辑可能在99%时触发了防抖，而在100%到来时虽然通过了if判断，但可能有并发更新问题
        // 这里显式放行所有完成态的更新
        if (progress >= 100 || task.status == DownloadStatus.COMPLETED) {
            lastProgressUpdateTimeMap.remove(task.id)
            // 此时应该触发全量刷新而不是 payload 刷新，因为需要改变按钮状态（从进度条变安装按钮）
            // 但为了复用逻辑，我们在 Adapter 里处理了 payload 的完成态
            updateListItemWithProgress(urgentList, binding.rvUrgent, task)
            updateListItemWithProgress(highList, binding.rvHigh, task)
            updateListItemWithProgress(normalList, binding.rvNormal, task)
            return
        }

        // --- 以下是未完成时的防抖逻辑 ---
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
            // 防御性检查：如果UI上已经是完成状态，且当前更新的不是完成状态（比如迟到的99%），则忽略
            if (currentItem.task?.status == DownloadStatus.COMPLETED && task.status != DownloadStatus.COMPLETED) {
                return
            }

            currentItem.task = task
            rv.post {
                // ✅ 传入 payload，触发 onPayload 回调进行局部刷新
                rv.adapter?.notifyItemChanged(idx, "PROGRESS_UPDATE")
            }
        }
    }

    private fun openDetail(item: DownloadItem) {
        val i = Intent(this, AppDetailActivity::class.java).apply {
            putExtra("url", item.url)
            putExtra("name", item.name)
            putExtra("packageName", item.packageName)
            putExtra("size", item.size)
            putExtra("icon", item.icon)
            putExtra("priority", item.priority)
        }
        startActivity(i)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横向列表不需要修改
    }

    override fun onDestroy() {
        floatBallView?.dismiss()
        floatBallView = null
        super.onDestroy()
    }

    private fun showFloatBall() {
        if (floatBallView == null) {
            floatBallView = FloatBallView(this).apply {
                setOnFloatClickListener {
                    startActivity(Intent(this@MainActivity, DownloadManagerActivity::class.java))
                }
                setOnDismissListener {
                    floatBallView = null
                }
            }
        }
        floatBallView?.show()
    }

    private fun updateFloatBallProgress(task: DownloadTask, progress: Int, speed: Long) {
        val speedText = formatSpeed(speed)
        val allLists = urgentList + highList + normalList
        val appName = allLists.find { it.url == task.url }?.name ?: task.fileName
        floatBallView?.updateProgress(appName, progress, speedText)
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> String.format("%.1fMB/s", bytesPerSecond / (1024.0 * 1024.0))
            bytesPerSecond >= 1024 -> String.format("%.0fKB/s", bytesPerSecond / 1024.0)
            else -> "${bytesPerSecond}B/s"
        }
    }
}