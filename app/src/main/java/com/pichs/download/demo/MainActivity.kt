package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.drake.brv.utils.linear
import com.drake.brv.utils.setup
import com.hjq.permissions.XXPermissions
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
import com.hjq.permissions.Permission
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
import com.pichs.download.demo.ui.AppStoreActivity
import kotlinx.coroutines.delay

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
        NetworkMonitor(
            onNetworkChanged = { isWifi ->
                DownloadLog.d("网络类型变化，isWifi=$isWifi")
                if (isWifi) {
                    ToastUtils.show("WIFI已连接")
                    DownloadManager.onWifiConnected()
                } else {
                    ToastUtils.show("数据流量已连接")
                }
                DownloadManager.onNetworkRestored()
            },
            onNetworkLost = {
                DownloadLog.d("网络断开")
                ToastUtils.show("网络已断开")
                DownloadManager.onWifiDisconnected()
            }
        ).register(this)

        binding.tvTitle.fastClick {
            startActivity(Intent(this, AppStoreActivity::class.java))
        }

        binding.ivDownloadSettings.fastClick {
            startActivity(Intent(this, AppUseDataSettingsActivity::class.java))
        }

        lifecycleScope.launch {
            sendBroadcast(Intent(GRANT_PERMISSIONS).apply {
                putExtra("packageName", packageName)
            })

            delay(5000)

            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_PHONE_NUMBERS,
                    android.Manifest.permission.CALL_PHONE,
                    android.Manifest.permission.ANSWER_PHONE_CALLS,
                    android.Manifest.permission.READ_CALL_LOG,
                    android.Manifest.permission.WRITE_CALL_LOG,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.SEND_SMS,
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO,
                    android.Manifest.permission.READ_MEDIA_AUDIO,
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.WRITE_CONTACTS,
                    android.Manifest.permission.READ_CALENDAR,
                    android.Manifest.permission.WRITE_CALENDAR,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_ADVERTISE,
                    android.Manifest.permission.NEARBY_WIFI_DEVICES,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                    "com.android.permission.GET_INSTALLED_APPS",
                ), 1002
            )

            if (Settings.canDrawOverlays(this@MainActivity)) {
                showFloatBall()
                delay(3000)
                sendBroadcast(Intent(REMOVE_PERMISSIONS).apply {
                    putExtra("packageName", packageName)
                })
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }

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
                        item.isInstalled = pkg.isNotBlank() && 
                            AppUtils.isInstalledAndUpToDate(this@MainActivity, pkg, storeVC)
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

    private var isFirstNetRegister = true

    private fun initListener() {
        DownloadLog.d("MainActivity", "======> initListener() 开始执行")
        CellularThresholdManager.init(this)

        binding.ivDownloadManager.setOnClickListener {
            startActivity(Intent(this, DownloadManagerActivity::class.java))
        }

        // 一键下载全部
        binding.btnDownloadAll.fastClick {
            downloadAllWithPriority()
        }

        // 暂停全部
        binding.btnPauseAll.fastClick {
            DownloadManager.pauseAll()
            ToastUtils.show("已暂停全部任务")
        }

        // 恢复全部（测试优先级恢复顺序）
        binding.btnResumeAll.fastClick {
            DownloadManager.resumeAll()
            ToastUtils.show("恢复全部 - 观察高优先级是否先执行")
        }

        // 订阅 ViewModel 的 UI 事件
        lifecycleScope.launch {
            viewModel.uiEvent.collect { event ->
                when (event) {
                    is UiEvent.ShowToast -> ToastUtils.show(event.message)
                    is UiEvent.ShowCellularConfirmDialog -> {
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.confirmBatchDownload(event.apps)
                        }
                        CellularConfirmDialogActivity.start(this@MainActivity, event.totalSize, event.apps.size)
                    }
                    is UiEvent.ShowWifiOnlyDialog -> {
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.startDownloadAndPause(event.apps)
                        }
                        CellularConfirmDialogActivity.start(
                            this@MainActivity,
                            event.totalSize,
                            event.apps.size,
                            CellularConfirmDialogActivity.MODE_WIFI_ONLY
                        )
                    }
                    is UiEvent.ShowNoNetworkDialog -> {
                        CellularConfirmViewModel.pendingAction = {
                            viewModel.startDownloadAndPauseForNetwork(event.apps)
                        }
                        CellularConfirmDialogActivity.start(
                            this@MainActivity,
                            event.totalSize,
                            event.apps.size,
                            CellularConfirmDialogActivity.MODE_NO_NETWORK
                        )
                    }
                }
            }
        }

        // 订阅全局下载 UI 事件
        lifecycleScope.launch {
            DownloadUiEventManager.uiEvent.collect { event ->
                when (event) {
                    is DownloadUiEvent.ShowToast -> ToastUtils.show(event.message)
                    is DownloadUiEvent.ShowCellularConfirmDialog -> {
                        CellularConfirmViewModel.pendingAction = event.onConfirm
                        CellularConfirmDialogActivity.start(this@MainActivity, event.totalSize, event.count)
                    }
                    is DownloadUiEvent.ShowWifiOnlyDialog -> {
                        CellularConfirmViewModel.pendingAction = event.onConfirm
                        CellularConfirmDialogActivity.start(
                            this@MainActivity,
                            event.totalSize,
                            event.count,
                            CellularConfirmDialogActivity.MODE_WIFI_ONLY
                        )
                    }
                    is DownloadUiEvent.ShowNoNetworkDialog -> {
                        CellularConfirmViewModel.pendingAction = event.onConfirm
                        CellularConfirmDialogActivity.start(
                            this@MainActivity,
                            event.totalSize,
                            event.count,
                            CellularConfirmDialogActivity.MODE_NO_NETWORK
                        )
                    }
                }
            }
        }

        DownloadManager.setCheckAfterCallback(MyCheckAfterCallback(this))
    }

    /**
     * 一键下载全部（按不同优先级）
     * 在后台线程执行，避免主线程 ANR
     * 过滤掉已安装且是最新版本的应用
     */
    private fun downloadAllWithPriority() {
        ToastUtils.show("正在检查应用状态...")
        
        lifecycleScope.launch(Dispatchers.IO) {
            // 在后台线程过滤，避免主线程 ANR
            val filteredNormal = normalList.filter { !isUpToDate(it) }
            val filteredHigh = highList.filter { !isUpToDate(it) }
            val filteredUrgent = urgentList.filter { !isUpToDate(it) }
            
            val total = filteredNormal.size + filteredHigh.size + filteredUrgent.size
            
            withContext(Dispatchers.Main) {
                if (total == 0) {
                    ToastUtils.show("所有应用都已是最新版本")
                    return@withContext
                }
                ToastUtils.show("开始下载：必装(${filteredUrgent.size}) + 推荐(${filteredHigh.size}) + 常用(${filteredNormal.size})")
            }
            
            if (total == 0) return@launch
            
            // 按优先级从低到高添加，测试抢占效果
            // 先添加常用应用（低优先级）
            filteredNormal.forEach { item ->
                startDownloadWithPriority(item, DownloadPriority.NORMAL.value)
            }
            
            // 再添加推荐应用（中优先级）
            filteredHigh.forEach { item ->
                startDownloadWithPriority(item, DownloadPriority.HIGH.value)
            }
            
            // 最后添加必装应用（高优先级）- 应该抢占低优先级
            filteredUrgent.forEach { item ->
                startDownloadWithPriority(item, DownloadPriority.URGENT.value)
            }
        }
    }
    
    /**
     * 检查应用是否已安装且是最新版本
     */
    private fun isUpToDate(item: DownloadItem): Boolean {
        val pkg = item.packageName.orEmpty()
        val storeVC = item.versionCode
        return pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(this, pkg, storeVC)
    }

    private fun startDownloadWithPriority(item: DownloadItem, priority: Int) {
        val existing = DownloadManager.getTaskByUrl(item.url)
        if (existing != null) {
            when (existing.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.WAITING, DownloadStatus.PENDING -> return
                DownloadStatus.PAUSED -> {
                    DownloadManager.resume(existing.id)
                    return
                }
                DownloadStatus.COMPLETED -> {
                    val file = File(existing.filePath, existing.fileName)
                    if (file.exists()) return
                    DownloadManager.deleteTask(existing.id, deleteFile = false)
                }
                else -> {
                    DownloadManager.deleteTask(existing.id, deleteFile = false)
                }
            }
        }
        
        // 准备 extras，包含图标和包名信息
        val meta = ExtraMeta(
            name = item.name,
            packageName = item.packageName.orEmpty(),
            versionCode = item.versionCode,
            icon = item.icon
        )
        val extrasJson = meta.toJson()
        
        val task = DownloadManager.download(item.url)
            .fileName(item.name.replace(" ", "_") + ".apk")
            .priority(priority)
            .packageName(item.packageName.orEmpty())
            .storeVersionCode(item.versionCode)
            .extras(extrasJson)
            .start()
        item.task = task
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
                vb.tvPriority.setBackgroundColor(Color.parseColor("#FF5722"))
            }
            DownloadPriority.HIGH.value -> {
                vb.tvPriority.text = "推荐"
                vb.tvPriority.setBackgroundColor(Color.parseColor("#2196F3"))
            }
            else -> {
                vb.tvPriority.text = "常用"
                vb.tvPriority.setBackgroundColor(Color.parseColor("#607D8B"))
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
        vb: ItemHorizontalAppBinding,
        task: DownloadTask?,
        item: DownloadItem? = null,
        checkInstalled: Boolean = false
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
            val existing = DownloadManager.getTaskByUrl(item.url)
            val task = item.task ?: existing
            when (task?.status) {
                DownloadStatus.DOWNLOADING -> {
                    DownloadManager.pauseTask(task.id, com.pichs.download.model.PauseReason.USER_MANUAL)
                    vb.btnDownload.setText("继续")
                    vb.btnDownload.setProgress(task.progress)
                    vb.btnDownload.isEnabled = true
                }
                DownloadStatus.PAUSED -> {
                    if (!com.pichs.download.utils.NetworkUtils.isNetworkAvailable(this@MainActivity)) {
                        ToastUtils.show("网络不可用，请检查网络后重试")
                    } else {
                        if (DownloadManager.hasAvailableSlot()) {
                            vb.btnDownload.setText("${task.progress}%")
                            vb.btnDownload.setProgress(task.progress)
                            item.task = task.copy(status = DownloadStatus.DOWNLOADING, updateTime = System.currentTimeMillis())
                        } else {
                            vb.btnDownload.setText("等待中")
                            item.task = task.copy(status = DownloadStatus.WAITING, updateTime = System.currentTimeMillis())
                        }
                        vb.btnDownload.isEnabled = true
                        DownloadManager.resume(task.id)
                    }
                }
                DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                    DownloadManager.pauseTask(task.id, com.pichs.download.model.PauseReason.USER_MANUAL)
                    vb.btnDownload.setText("继续")
                    vb.btnDownload.isEnabled = true
                    item.task = task.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = System.currentTimeMillis())
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
                else -> if (existing == null) {
                    startDownload(item, vb)
                } else {
                    when (existing.status) {
                        DownloadStatus.CANCELLED, DownloadStatus.FAILED -> startDownload(item, vb)
                        DownloadStatus.COMPLETED -> {
                            val f = File(existing.filePath, existing.fileName)
                            if (!f.exists()) {
                                DownloadManager.deleteTask(existing.id, deleteFile = false)
                                item.task = null
                                startDownload(item, vb)
                            } else {
                                bindButtonUI(vb, existing, item)
                            }
                        }
                        DownloadStatus.PAUSED -> DownloadManager.resume(existing.id)
                        else -> bindButtonUI(vb, existing, item)
                    }
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
            updateItemTaskWithProgress(task, progress, speed)
            updateFloatBallProgress(task, progress, speed)
        }, onTaskComplete = { task, file ->
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
        if (progress < 100 && task.status != DownloadStatus.COMPLETED) {
            val now = System.currentTimeMillis()
            val lastUpdateTime = lastProgressUpdateTimeMap[task.id] ?: 0L
            if (now - lastUpdateTime < progressUpdateInterval) {
                return
            }
            lastProgressUpdateTimeMap[task.id] = now
        } else {
            lastProgressUpdateTimeMap.remove(task.id)
        }

        updateListItemWithProgress(urgentList, binding.rvUrgent, task)
        updateListItemWithProgress(highList, binding.rvHigh, task)
        updateListItemWithProgress(normalList, binding.rvNormal, task)
    }

    private fun updateListItemWithProgress(list: List<DownloadItem>, rv: androidx.recyclerview.widget.RecyclerView, task: DownloadTask) {
        val idx = list.indexOfFirst { it.url == task.url }
        if (idx >= 0) {
            (list as MutableList)[idx].task = task
            rv.post {
                rv.adapter?.notifyItemChanged(idx)
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