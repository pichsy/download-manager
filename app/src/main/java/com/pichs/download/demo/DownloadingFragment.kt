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
import com.pichs.xbase.kotlinext.setItemAnimatorDisable
import kotlinx.coroutines.launch

/**
 * 正在下载列表 Fragment
 */
class DownloadingFragment : Fragment() {

    private var _binding: FragmentDownloadListBinding? = null
    private val binding get() = _binding!!

    private val downloading = mutableListOf<DownloadTask>()
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
        android.util.Log.d("DownloadDemo", "DownloadingFragment: onResume - refreshing list")
        refreshList()
    }

    private fun setupRecycler() {
        adapter = DownloadTaskAdapter(
            onButtonClick = { task -> handleAction(task) },
            onLoadIcon = { imageView, task -> loadIcon(imageView, task) }
        )
        adapter.onDelete = { task ->
            DeleteConfirmDialog(requireContext()) {
                DownloadManager.deleteTask(task.id, deleteFile = true)
                // 从 UI 移除
                removeFromList(task.id)
            }.showPopupWindow()
        }
        binding.recyclerView.setItemAnimatorDisable()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter.setHasStableIds(true)
        binding.recyclerView.adapter = adapter
    }

    private fun handleAction(task: DownloadTask) {
        when (task.status) {
            DownloadStatus.DOWNLOADING -> {
                DownloadManager.pause(task.id)
            }

            DownloadStatus.PAUSED -> {
                if (!com.pichs.download.utils.NetworkUtils.isNetworkAvailable(requireContext())) {
                    Toast.makeText(requireContext(), "网络不可用，请检查网络后重试", Toast.LENGTH_SHORT).show()
                } else {
                    val targetStatus = if (DownloadManager.hasAvailableSlot()) {
                        DownloadStatus.DOWNLOADING
                    } else {
                        DownloadStatus.WAITING
                    }
                    updateSingleImmediate(task.id, targetStatus)
                    DownloadManager.resume(task.id)
                }
            }

            DownloadStatus.PENDING, DownloadStatus.WAITING -> {
                DownloadManager.pause(task.id)
            }

            DownloadStatus.FAILED -> {
                if (!com.pichs.download.utils.NetworkUtils.isNetworkAvailable(requireContext())) {
                    Toast.makeText(requireContext(), "网络不可用，请检查网络后重试", Toast.LENGTH_SHORT).show()
                } else {
                    DownloadManager.resume(task.id)
                }
            }

            else -> {}
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
            android.util.Log.d("DownloadDemo", "DownloadingFragment: getAllTasks size=${all.size}")

            val sorted = all.sortedByDescending { it.updateTime }
                .distinctBy { it.id }

            downloading.clear()
            sorted.forEach { task ->
                when (task.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED,
                    DownloadStatus.PENDING, DownloadStatus.WAITING,
                    DownloadStatus.FAILED -> downloading.add(task)

                    else -> {}
                }
            }

            downloading.sortWith(
                compareByDescending<DownloadTask> { it.priority }
                    .thenByDescending { it.createTime }
            )
            android.util.Log.d("DownloadDemo", "DownloadingFragment: submitting list size=${downloading.size}")
            adapter.submit(downloading)
            updateEmptyState()
        }
    }

    private fun updateEmptyState() {
        binding.emptyView.visibility = if (downloading.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = "暂无下载任务"
    }

    private fun bindListeners() {
        DownloadManager.flowListener.bindToLifecycle(
            lifecycleOwner = viewLifecycleOwner,
            onTaskProgress = { task, progress, speed ->
                if (!isAdded) return@bindToLifecycle
                updateSingleWithProgress(task, progress, speed)
            },
            onTaskComplete = { task, file ->
                if (!isAdded) return@bindToLifecycle
                removeFromList(task.id)
            },
            onTaskError = { task, error ->
                if (!isAdded) return@bindToLifecycle
                updateSingle(task)
            },
            onTaskPaused = { task ->
                if (!isAdded) return@bindToLifecycle
                updateSingle(task)
            },
            onTaskResumed = { task ->
                if (!isAdded) return@bindToLifecycle
                updateSingle(task)
            },
            onTaskCancelled = { task ->
                if (!isAdded) return@bindToLifecycle
                removeFromList(task.id)
            }
        )
    }

    private fun updateSingle(task: DownloadTask) {
        val idx = downloading.indexOfFirst { it.id == task.id }
        if (idx >= 0) {
            downloading[idx] = task
            adapter.updateItem(task)
        }
    }

    private fun updateSingleWithProgress(task: DownloadTask, progress: Int, speed: Long) {
        val idx = downloading.indexOfFirst { it.id == task.id }
        if (idx >= 0) {
            downloading[idx] = task
            adapter.updateItemWithProgress(task, progress, speed)
        }
    }

    private fun updateSingleImmediate(taskId: String, newStatus: DownloadStatus) {
        val idx = downloading.indexOfFirst { it.id == taskId }
        if (idx >= 0) {
            val task = downloading[idx]
            val updated = task.copy(
                status = newStatus,
                speed = if (newStatus == DownloadStatus.PAUSED) 0L else task.speed,
                updateTime = System.currentTimeMillis()
            )
            downloading[idx] = updated
            adapter.updateItem(updated)
        }
    }

    private fun removeFromList(taskId: String) {
        val idx = downloading.indexOfFirst { it.id == taskId }
        if (idx >= 0) {
            android.util.Log.d("DownloadDemo", "DownloadingFragment: removing task $taskId")
            val task = downloading[idx]
            downloading.removeAt(idx)
            adapter.removeItem(task)
            updateEmptyState()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
