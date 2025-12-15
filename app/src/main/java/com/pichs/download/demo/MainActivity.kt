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
import com.pichs.download.core.DownloadPriority
import com.pichs.download.core.FlowDownloadListener
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.pichs.download.demo.databinding.ActivityMainBinding
import com.pichs.download.demo.databinding.ItemGridDownloadBeanBinding
import com.pichs.download.utils.DownloadLog
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.receiver.NetStateReceiver
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.kotlinext.fastClick
import com.pichs.xbase.kotlinext.setItemAnimatorDisable
import com.pichs.xbase.utils.GsonUtils
import java.io.File

class MainActivity : BaseActivity<ActivityMainBinding>() {

    private val list = arrayListOf<DownloadItem>()

    // 旧的监听器已移除，现在使用Flow监听器
    private val flowListener = DownloadManager.flowListener

    // 统一的名称归一化工具
    private fun normalizeName(n: String): String = n.substringBeforeLast('.').lowercase()

    override fun afterOnCreate() {

        binding.ivDownloadSettings.fastClick {
            startActivity(Intent(this, AppUseDataSettingsActivity::class.java))
        }
        XXPermissions.with(this).unchecked().permission(Permission.MANAGE_EXTERNAL_STORAGE).permission(Permission.REQUEST_INSTALL_PACKAGES).request { _, _ -> }

        initListener()

        val appJsonStr = assets.open("app_list.json").bufferedReader().use { it.readText() }
        val appListBean = GsonUtils.fromJson<AppListBean>(appJsonStr, AppListBean::class.java)
        appListBean.appList?.let { list.addAll(it) }
        // 将首页的数据注册到进程内注册表，供其他页面优先读取
        AppMetaRegistry.registerAll(list)

        initRecyclerView()
        // 绑定全局监听（新方式）
        bindFlowListener()

        // 模拟批量后台下载（延迟 10 秒）
        binding.root.postDelayed({
            simulateBatchDownload()
        }, 10_000)
    }

    /**
     * 模拟批量后台下载
     * 从列表中选 5 个应用进行批量下载
     */
    private fun simulateBatchDownload() {

        // 选 5 个包名（写死）
        val targetPackages = listOf(
            "com.phoenix.read",      // 红果免费短剧
            "com.kugou.android",     // 酷狗音乐
            "tv.danmaku.bili",       // 哔哩哔哩
            "com.ss.android.ugc.aweme", // 抖音
            "com.tencent.mm"         // 微信
        )

        // 从列表中筛选出这 5 个应用
        val appsToDownload = list.filter { it.packageName in targetPackages }

        if (appsToDownload.isEmpty()) {
            DownloadLog.d("模拟批量下载", "未找到目标应用")
            return
        }

        // 计算总大小
        val totalSize = appsToDownload.sumOf { it.size }
        val config = DownloadManager.getNetworkConfig()
        val isWifi = DownloadManager.isWifiAvailable()

        DownloadLog.d("模拟批量下载", "准备下载 ${appsToDownload.size} 个应用，总大小: $totalSize, WiFi: $isWifi")

        // 使用端智能判断逻辑
        when {
            // WiFi 可用，直接下载
            isWifi -> {
                startBatchDownload(appsToDownload)
            }
            // 仅 WiFi 模式
            config.wifiOnly -> {
                runOnUiThread {
                    ToastUtils.show("当前设置为仅 WiFi 下载，请连接 WiFi")
                }
            }
            // 允许流量 + 不提醒
            config.cellularPromptMode == com.pichs.download.model.CellularPromptMode.NEVER -> {
                startBatchDownload(appsToDownload)
            }
            // 允许流量 + 交给用户（智能提醒）
            config.cellularPromptMode == com.pichs.download.model.CellularPromptMode.USER_CONTROLLED -> {
                if (CellularThresholdManager.shouldPrompt(totalSize)) {
                    // 超过阈值，弹窗确认
                    showBatchDownloadConfirmDialog(appsToDownload, totalSize)
                } else {
                    // 未超阈值，静默下载
                    DownloadLog.d("模拟批量下载", "智能提醒：未超阈值，静默下载")
                    DownloadManager.markCellularDownloadAllowed()
                    startBatchDownload(appsToDownload)
                }
            }
            // 允许流量 + 每次提醒
            else -> {
                showBatchDownloadConfirmDialog(appsToDownload, totalSize)
            }
        }
    }

    private fun showBatchDownloadConfirmDialog(apps: List<DownloadItem>, totalSize: Long) {
        val sizeText = formatFileSize(totalSize)
        android.app.AlertDialog.Builder(this).setTitle("流量下载提醒").setMessage("当前使用移动网络，将下载 ${apps.size} 个应用共 $sizeText\n确定使用流量下载？")
            .setNeutralButton("取消") { dialog, _ -> dialog.dismiss() }.setNegativeButton("连接 WiFi") { _, _ ->
                runCatching {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                }
            }.setPositiveButton("使用流量") { _, _ ->
                DownloadManager.markCellularDownloadAllowed()
                startBatchDownload(apps)
            }.setCancelable(false).show()
    }

    private fun startBatchDownload(apps: List<DownloadItem>) {
        DownloadLog.d("模拟批量下载", "开始创建 ${apps.size} 个任务")
        val dir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
        apps.forEach { app ->
            val fileName = if (app.name.endsWith(".apk", ignoreCase = true)) {
                app.name
            } else {
                "${app.name}.apk"
            }
            DownloadManager.download(app.url).path(dir).fileName(fileName).estimatedSize(app.size).start()
        }
        runOnUiThread {
            ToastUtils.show("批量下载已开始：${apps.size} 个应用")
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }


    private var isFirstNetRegister = true

    private fun initListener() {
        // 初始化阈值管理器
        CellularThresholdManager.init(this)
        
        binding.ivDownloadManager.setOnClickListener {
            startActivity(Intent(this, DownloadManagerActivity::class.java))
        }

        binding.ivSearch.fastClick {
            simulateBatchDownload()
        }

        // 设置网络决策回调
        DownloadManager.setDecisionCallback(MyDownloadDecisionCallback(this))

        NetStateReceiver(onNetConnected = { isWifi ->
            // 网络恢复时
            if (!isFirstNetRegister) {
                DownloadLog.d("网络连接成功，isWifi=$isWifi")
                if (isWifi) {
                    // WiFi 连接：重置流量会话，恢复 WiFi 暂停的任务
                    DownloadManager.onWifiConnected()
                }
                // 通用网络恢复：恢复网络异常暂停的任务
                DownloadManager.onNetworkRestored()
            }
            isFirstNetRegister = false
        }, onNetDisConnected = {
            if (!isFirstNetRegister) {
                DownloadLog.d("网络连接断开")
                // 通知网络规则管理器处理 WiFi 断开
                DownloadManager.onWifiDisconnected()
                // 网络断开时，暂停正在下载的任务
                DownloadManager.pauseAllForNetworkError()
            }
            isFirstNetRegister = false
        }).register(this)
    }

    @SuppressLint("SetTextI18n")
    private fun initRecyclerView() {
        binding.recyclerView.setItemAnimatorDisable()
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
                // 冷启动映射历史任务到列表项
                runCatching {
                    val dirBind = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
                    lifecycleScope.launch {
                        val existingTask = DownloadManager.getAllTasks().firstOrNull {
                            it.url == item.url && it.filePath == dirBind && normalizeName(it.fileName) == normalizeName(item.name)
                        }
                        if (existingTask != null) item.task = existingTask
                    }
                }
                bindButtonUIWithInstalledAndFile(vb, item)
                vb.btnDownload.setOnClickListener { handleClickWithInstalled(item, vb) }
                // 新增：点击封面跳转详情页
                vb.ivCover.setOnClickListener { openDetail(item) }
            }
        }.models = list
    }

    private fun bindButtonUIWithInstalledAndFile(vb: ItemGridDownloadBeanBinding, item: DownloadItem) {
        val task = item.task
        // 直接使用 item 自带的包名+版本信息
        val pkg = item.packageName.orEmpty()
        val storeVC = item.versionCode
        val canOpen = pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(this, pkg, storeVC)
        if (canOpen) {
            vb.btnDownload.setText("打开")
            vb.btnDownload.isEnabled = true
            return
        }
        // 回退到原绑定逻辑，但在 COMPLETED 时考虑文件健康
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
                vb.btnDownload.setText("${task.progress}%"); vb.btnDownload.setProgress(task.progress); vb.btnDownload.isEnabled = true
            }

            DownloadStatus.PAUSED -> {
                // 暂停时显示“继续”，进度条保留进度
                vb.btnDownload.setText("继续"); vb.btnDownload.setProgress(task.progress); vb.btnDownload.isEnabled = true
            }

            DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                vb.btnDownload.setText("等待中"); vb.btnDownload.isEnabled = true
            }

            DownloadStatus.FAILED -> {
                vb.btnDownload.setText("重试"); vb.btnDownload.isEnabled = true
            }

            else -> {
                vb.btnDownload.setText("下载"); vb.btnDownload.setProgress(0); vb.btnDownload.isEnabled = true
            }
        }
    }

    private fun handleClickWithInstalled(item: DownloadItem, vb: ItemGridDownloadBeanBinding) {
        // 直接使用 item 自带的包名+版本信息
        val pkg = item.packageName.orEmpty()
        val storeVC = item.versionCode
        val canOpen = pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(this, pkg, storeVC)
        if (canOpen) {
            if (!AppUtils.openApp(this, pkg)) {
                ToastUtils.show("无法打开应用")
            }
            return
        }
        handleClick(item, vb)
    }

    private fun handleClick(item: DownloadItem, vb: ItemGridDownloadBeanBinding) {
        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        val name = item.name
        lifecycleScope.launch {
            val existing = DownloadManager.getAllTasks().firstOrNull {
                it.url == item.url && it.filePath == dir && normalizeName(it.fileName) == normalizeName(name)
            }
            val task = item.task ?: existing
            when (task?.status) {
                DownloadStatus.DOWNLOADING -> {
                    DownloadManager.pause(task.id)
                    // 暂停后应显示“继续”，进度条保持当前进度
                    vb.btnDownload.setText("继续")
                    vb.btnDownload.setProgress(task.progress)
                    vb.btnDownload.isEnabled = true
                }

                DownloadStatus.PAUSED -> {
                    DownloadManager.resume(task.id)
                    // 不强制设置文案，等待调度后通过监听刷新
                }

                DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                    // 等待中也可暂停：从队列移出，切换为“继续”
                    DownloadManager.pause(task.id)
                    vb.btnDownload.setText("继续")
                    vb.btnDownload.isEnabled = true
                    // 立刻更新本地模型，避免下一次点击仍按 WAITING 分支
                    item.task = task.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = System.currentTimeMillis())
                }

                DownloadStatus.COMPLETED -> {
                    val file = File(task.filePath, task.fileName)
                    if (file.exists()) {
                        openApk(task)
                    } else {
                        // 文件缺失：清理任务并直接重启下载
                        DownloadManager.deleteTask(task.id, deleteFile = false)
                        item.task = null
                        startDownload(item, vb)
                    }
                }

                DownloadStatus.FAILED -> startDownload(item, vb)
                else -> if (existing == null) {
                    startDownload(item, vb)
                } else {
                    // 存在旧任务但状态不适配时的处理：
                    when (existing.status) {
                        DownloadStatus.CANCELLED, DownloadStatus.FAILED -> {
                            startDownload(item, vb)
                        }

                        DownloadStatus.COMPLETED -> {
                            val f = File(existing.filePath, existing.fileName)
                            if (!f.exists()) {
                                DownloadManager.deleteTask(existing.id, deleteFile = false)
                                item.task = null
                                startDownload(item, vb)
                            } else {
                                bindButtonUI(vb, existing)
                            }
                        }

                        DownloadStatus.PAUSED -> {
                            DownloadManager.resume(existing.id)
                        }

                        else -> bindButtonUI(vb, existing)
                    }
                }
            }
        }
    }

    private fun startDownload(item: DownloadItem, vb: ItemGridDownloadBeanBinding) {
        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        // 将图标/名称/包名/版本写入 extras(JSON)
//        val extrasJson = com.pichs.xbase.utils.GsonUtils.toJson(
//            mapOf(
//                "name" to (item.name ?: ""),
//                "packageName" to (item.packageName ?: ""),
//                "versionCode" to (item.versionCode ?: 0L),
//                "icon" to (item.icon ?: "")
//            )
//        )
        // 文件名带上 .apk 后缀
        val fileName1 = if (item.name.isEmpty()) {
            "1.apk"
        } else if (item.name.endsWith(".apk", ignoreCase = true)) {
            item.name
        } else {
            "${item.name}.apk"
        }
        // 使用新的优先级API，用户主动下载使用HIGH优先级
        val task = DownloadManager.downloadWithPriority(item.url, DownloadPriority.HIGH).path(dir).fileName(fileName1).estimatedSize(item.size).start()
        item.task = task
        bindButtonUIWithInstalledAndFile(vb, item)
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
                vb.btnDownload.setText("等待中"); vb.btnDownload.isEnabled = true
            }

            DownloadStatus.COMPLETED -> {
                val health = task.let { AppUtils.checkFileHealth(it) }
                if (health == AppUtils.FileHealth.OK) {
                    vb.btnDownload.setText("安装")
                    vb.btnDownload.setProgress(100)
                    vb.btnDownload.isEnabled = true
                } else {
                    // 文件缺失/损坏：回落为下载态
                    vb.btnDownload.setText("下载")
                    vb.btnDownload.setProgress(0)
                    vb.btnDownload.isEnabled = true
                }
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

    // 新增：绑定Flow监听，保持列表项与任务状态同步
    private fun bindFlowListener() {
        flowListener.bindToLifecycle(lifecycleOwner = this, onTaskProgress = { task, progress, speed ->
            if (isDestroyed) return@bindToLifecycle
            // 更新任务进度和速度
            updateItemTaskWithProgress(task, progress, speed)
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
        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        val idx = list.indexOfFirst {
            it.url == task.url && dir == task.filePath && normalizeName(it.name) == normalizeName(task.fileName)
        }
        if (idx >= 0) {
            list[idx].task = task
            binding.recyclerView.adapter?.notifyItemChanged(idx)
        }
    }

    // 专门处理进度更新的方法（添加防抖机制）
    private val lastProgressUpdateTimeMap = mutableMapOf<String, Long>()
    private val progressUpdateInterval = 300L // 300ms防抖间隔

    private fun updateItemTaskWithProgress(task: DownloadTask, progress: Int, speed: Long) {
        val now = System.currentTimeMillis()
        val lastUpdateTime = lastProgressUpdateTimeMap[task.id] ?: 0L
        if (now - lastUpdateTime < progressUpdateInterval) {
            return // 防抖：跳过过于频繁的更新
        }
        lastProgressUpdateTimeMap[task.id] = now

        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        val idx = list.indexOfFirst {
            it.url == task.url && dir == task.filePath && normalizeName(it.name) == normalizeName(task.fileName)
        }
        if (idx >= 0) {
            // 更新任务数据
            list[idx].task = task
            // 使用post延迟更新UI，避免阻塞主线程
            binding.recyclerView.post {
                binding.recyclerView.adapter?.notifyItemChanged(idx)
            }
        }
    }

    // 更新ViewHolder中的进度显示
    private fun updateProgressInViewHolder(
        viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, progress: Int, speed: Long, status: com.pichs.download.model.DownloadStatus
    ) {
        // 这里需要根据实际的ViewHolder结构来更新
        // 由于使用了BRV库，我们需要通过其他方式来更新进度
        // 暂时使用notifyItemChanged来触发重新绑定
        binding.recyclerView.adapter?.notifyItemChanged(viewHolder.adapterPosition)
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
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.recyclerView.post { binding.recyclerView.grid(7).adapter?.notifyDataSetChanged() }
        } else {
            binding.recyclerView.grid(4).adapter?.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        // Flow监听器会自动管理生命周期，无需手动移除
        super.onDestroy()
    }
}