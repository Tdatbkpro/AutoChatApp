package com.example.autochat.ui.phone.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.autochat.databinding.FragmentModelListBinding
import com.example.autochat.llm.ModelManager
import com.example.autochat.ui.phone.adapter.ModelAdapter
import com.example.autochat.ui.phone.viewmodel.DownloadState
import com.example.autochat.ui.phone.viewmodel.ModelViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ModelListFragment : Fragment() {

    private var _binding: FragmentModelListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ModelViewModel by activityViewModels()
    private lateinit var adapter: ModelAdapter

    @Inject
    lateinit var modelManager: ModelManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentModelListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAdapter()
        observeViewModel()
        showStorageDetails()

        viewModel.loadModels()
    }

    private fun setupAdapter() {
        adapter = ModelAdapter(
            onDownload = { model -> onDownloadClicked(model) },
            onPause = { model -> viewModel.pauseDownload(model.id) },
            onResume = { model -> viewModel.resumeDownload(model.id) },
            onSelect = { model ->
                viewModel.selectModel(model.id)
                // Đóng bottom sheet sau khi chọn
                (parentFragment as? ModelManagerFragment)?.dismiss()
            },
            onDelete = { model -> viewModel.deleteModel(model.id) }
        )

        binding.rvModels.apply {
            adapter = this@ModelListFragment.adapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun onDownloadClicked(model: ModelManager.ModelInfo) {
        viewLifecycleOwner.lifecycleScope.launch {
            val token = viewModel.hfToken.value
            if (token.isNullOrBlank()) {
                showTokenDialog { viewModel.downloadModel(model.id) }
            } else {
                viewModel.downloadModel(model.id)
            }
        }
    }

    private fun showTokenDialog(onConfirm: () -> Unit) {
        val dialogBinding = com.example.autochat.databinding.DialogHfTokenBinding.inflate(layoutInflater)

        val currentToken = viewModel.hfToken.value
        if (!currentToken.isNullOrBlank()) {
            dialogBinding.tvCurrentToken.text = "Token hiện tại: ...${currentToken.takeLast(6)}"
            dialogBinding.tvCurrentToken.visibility = View.VISIBLE
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton("Lưu & Tải") { _, _ ->
                val input = dialogBinding.etToken.text?.toString()?.trim()
                if (!input.isNullOrBlank()) viewModel.saveHFToken(input)
                onConfirm()
            }
            .setNegativeButton("Bỏ qua") { _, _ -> onConfirm() }
            .setNeutralButton("Xóa token") { _, _ ->
                viewModel.clearHFToken()
                onConfirm()
            }
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.models,
                viewModel.downloadStates,  // ✅ Chỉ cần 1 flow này
                viewModel.downloadingIds,
                viewModel.pausedIds,
                viewModel.activeModelId
            ) { models, downloadStates, downloading, paused, activeId ->
                models.map { model ->
                    val isDownloading = downloading.contains(model.id)
                    val isPaused = paused.contains(model.id)
                    val ds = downloadStates[model.id] ?: DownloadState()

                    ModelAdapter.ModelState(
                        info = model.copy(isDownloaded = model.isDownloaded && !isDownloading && !isPaused),
                        isDownloading = isDownloading,
                        isPaused = isPaused,
                        progress = ds.progress,
                        speed = ds.speed,
                        downloadedBytes = ds.downloadedBytes,
                        totalBytes = ds.totalBytes,
                        isActive = model.id == activeId
                    )
                }
            }.collect { states ->
                adapter.submitList(states)
                updateStorageInfo(states.map { it.info })
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.statusText.collect { binding.tvStatusDesc.text = it }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isOnline.collect { online ->
                binding.tvOnlineStatus.text = if (online) "🟢 Online" else "🔴 Offline"
            }
        }
    }

    private fun showStorageDetails() {
        binding.tvStorageInfo.setOnClickListener {
            val info = modelManager.getStorageInfo()
            val message = buildString {
                appendLine("📊 Thông tin lưu trữ:")
                appendLine("• Model đã tải: ${info.modelCount}")
                appendLine("• Dung lượng đã dùng: ${info.usedMB} MB")
                appendLine("• Dung lượng trống: ${info.freeMB} MB")
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStorageInfo(models: List<ModelManager.ModelInfo>) {
        val storageInfo = modelManager.getStorageInfo()
        binding.tvStorageInfo.text = "${storageInfo.modelCount}/${models.size} model"
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadModels()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}