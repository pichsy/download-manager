package com.pichs.download.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.databinding.FragmentDownloadListBinding
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.model.DownloadTaskOrder
import com.pichs.shanhai.base.api.entity.qiniuHostUrl
import com.pichs.xbase.kotlinext.setItemAnimatorDisable
import kotlinx.coroutines.launch

/**
 * 正在下载列表 Fragment
 */
class DownloadingFragment : Fragment() {

    private var _binding: FragmentDownloadListBinding? = null
    private val binding get() = _binding!!

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
        observeTasks()
    }

    private fun setupRecycler() {
        adapter = DownloadTaskAdapter(
            onButtonClick = { task -> handleAction(task) },
            onLoadIcon = { imageView, task -> loadIcon(imageView, task) }
        )
        adapter.onDelete = { task ->
            DeleteConfirmDialog(requireContext()) {
                DownloadManager.deleteTask(task.id, deleteFile = true)
            }.showPopupWindow()
        }
        binding.recyclerView.setItemAnimatorDisable()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter.setHasStableIds(true)
        binding.recyclerView.adapter = adapter
    }

    private fun observeTasks() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadManager.flowDownloadListener
                    .observeTasks(ACTIVE_STATUSES, DownloadTaskOrder.PRIORITY)
                    .collect { tasks ->
                        adapter.submitList(tasks)
                        updateEmptyState(tasks.isEmpty())
                    }
            }
        }
    }

    private fun handleAction(task: DownloadTask) {
        when (task.status) {
            DownloadStatus.DOWNLOADING -> {
                DownloadManager.pause(task.id)
            }

            DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                if (!com.pichs.download.utils.NetworkUtils.isNetworkAvailable(requireContext())) {
                    Toast.makeText(requireContext(), "网络不可用，请检查网络后重试", Toast.LENGTH_SHORT).show()
                } else {
                    DownloadManager.resume(task.id)
                }
            }

            DownloadStatus.PENDING, DownloadStatus.WAITING -> {
                DownloadManager.pause(task.id)
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

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.tvEmpty.text = "暂无下载任务"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private val ACTIVE_STATUSES = setOf(
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PAUSED,
            DownloadStatus.PENDING,
            DownloadStatus.WAITING,
            DownloadStatus.FAILED
        )
    }
}
