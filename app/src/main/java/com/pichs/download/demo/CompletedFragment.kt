package com.pichs.download.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.databinding.FragmentDownloadListBinding
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.shanhai.base.api.entity.qiniuHostUrl
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.xbase.kotlinext.setItemAnimatorDisable
import kotlinx.coroutines.launch

/**
 * 下载完成列表 Fragment
 */
class CompletedFragment : Fragment() {

    private var _binding: FragmentDownloadListBinding? = null
    private val binding get() = _binding!!

    private val completed = mutableListOf<DownloadTask>()
    private lateinit var adapter: DownloadTaskAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecycler()
        bindListeners()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.d("DownloadDemo", "CompletedFragment: onResume - refreshing list")
        refreshList()
    }

    private fun setupRecycler() {
        adapter = DownloadTaskAdapter(
            onButtonClick = { task -> handleClickItem(task) },
            onLoadIcon = { imageView, task -> loadIcon(imageView, task) }
        )
        adapter.onDelete = { task ->
            DeleteConfirmDialog(requireContext()) {
                DownloadManager.deleteTask(task.id, deleteFile = true)
                removeFromList(task.id)
            }.showPopupWindow()
        }
        binding.recyclerView.setItemAnimatorDisable()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter.setHasStableIds(true)
        binding.recyclerView.adapter = adapter
    }

    private fun handleClickItem(task: DownloadTask) {
        val meta = ExtraMeta.fromJson(task.extras)
        val pkg = meta?.packageName
            ?: AppUtils.getPackageNameForTask(requireContext(), task)
            ?: ""
        val storeVC = meta?.versionCode ?: AppUtils.getStoreVersionCode(requireContext(), pkg)

        // 如果已安装且是最新版本，打开应用
        if (pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(requireContext(), pkg, storeVC)) {
            if (!AppUtils.openApp(requireContext(), pkg)) {
                Toast.makeText(requireContext(), "无法打开应用", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // 检查文件健康状态
        val health = AppUtils.checkFileHealth(task)
        if (health == AppUtils.FileHealth.OK) {
            openApk(task)
        } else {
            // 文件缺失或损坏，删除旧任务并重新下载
            viewLifecycleOwner.lifecycleScope.launch {
                DownloadManager.deleteTask(task.id, deleteFile = true)
                removeFromList(task.id)

                // 重新创建下载任务
                val dir = requireContext().externalCacheDir?.absolutePath
                    ?: requireContext().cacheDir.absolutePath
                DownloadManager.download(task.url)
                    .path(dir)
                    .fileName(task.fileName)
                    .start()
                Toast.makeText(requireContext(), "开始重新下载", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeFromList(taskId: String) {
        val idx = completed.indexOfFirst { it.id == taskId }
        if (idx >= 0) {
            android.util.Log.d("DownloadDemo", "CompletedFragment: removing task $taskId")
            val task = completed[idx]
            completed.removeAt(idx)
            adapter.removeItem(task)
            updateEmptyState()
        }
    }

    private fun loadIcon(imageView: android.widget.ImageView, task: DownloadTask) {
        val meta = ExtraMeta.fromJson(task.extras)
        val iconUrl = meta?.icon?.qiniuHostUrl
        if (!iconUrl.isNullOrBlank()) {
            Glide.with(imageView).load(iconUrl).into(imageView)
        } else {
            val pkg = meta?.packageName ?: ""
            if (pkg.isNotBlank()) {
                runCatching { requireContext().packageManager.getApplicationIcon(pkg) }
                    .onSuccess { imageView.setImageDrawable(it) }
                    .onFailure { imageView.setImageResource(R.color.purple_200) }
            } else {
                imageView.setImageResource(R.color.purple_200)
            }
        }
    }

    private fun refreshList() {
        viewLifecycleOwner.lifecycleScope.launch {
            val all = DownloadManager.getAllTasks()
            android.util.Log.d("DownloadDemo", "CompletedFragment: getAllTasks size=${all.size}")

            val filtered = all.filter { it.status == DownloadStatus.COMPLETED }
                .sortedByDescending { it.updateTime }
                .distinctBy { it.id }

            android.util.Log.d("DownloadDemo", "CompletedFragment: completed size=${filtered.size}")

            completed.clear()
            completed.addAll(filtered)
            adapter.submit(completed)
            updateEmptyState()
        }
    }

    private fun updateEmptyState() {
        binding.emptyView.visibility = if (completed.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = "暂无已完成任务"
    }

    private fun bindListeners() {
        DownloadManager.flowListener.bindToLifecycle(
            lifecycleOwner = viewLifecycleOwner,
            onTaskComplete = { task, file ->
                LogUtils.d("DownloadDemo", "CompletedFragment:bindListeners onTaskComplete： task=${task.fileName}, file=${file.absolutePath}")
                if (!isAdded) return@bindToLifecycle
                refreshList()
            }
        )
    }

    private fun openApk(task: DownloadTask) {
        val file = java.io.File(task.filePath, task.fileName)
        if (!file.exists()) return
        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
