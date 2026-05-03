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
import com.example.autochat.R
import com.example.autochat.databinding.DialogAddCustomModelBinding
import com.example.autochat.databinding.FragmentModelListBinding
import com.example.autochat.llm.ModelManager
import com.example.autochat.llm.ModelValidator
import com.example.autochat.ui.phone.adapter.GGUFAdapter
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

    @Inject lateinit var modelValidator: ModelValidator

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentModelListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupAdapter()
        observeViewModel()
        showStorageDetails()
        viewModel.loadModels()


        binding.btnAddCustom.setOnClickListener {
            showAddCustomModelDialog()
        }
    }

    private fun setupAdapter() {
        adapter = ModelAdapter(
            onDownload = { model -> onDownloadClicked(model) },
            onPause    = { model -> viewModel.pauseDownload(model.id) },    // ← Tạm dừng, giữ file
            onResume   = { model -> viewModel.resumeDownload(model.id) },   // ← Tiếp tục từ chỗ dừng
            onCancel   = { model -> viewModel.cancelDownload(model.id) },   // ← Hủy + xóa file
            onSelect   = { model ->
                viewModel.selectModel(model.id)
                (parentFragment as? ModelManagerFragment)?.dismiss()
            },
            onDelete   = { model -> viewModel.deleteModel(model.id) },
            onEdit     = { model -> showEditCustomModelDialog(model) }
        )

        binding.rvModels.apply {
            adapter = this@ModelListFragment.adapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }
    private fun showEditCustomModelDialog(model: ModelManager.ModelInfo) {
        val dialogBinding = DialogAddCustomModelBinding.inflate(LayoutInflater.from(requireContext()))

        // Pre-fill
        dialogBinding.etModelDesc.setText(model.description)
        dialogBinding.btnCheckRepo.visibility = View.VISIBLE  // ✅ Hiện nút check repo

        val dialog = android.app.Dialog(requireContext(), R.style.DialogTheme)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        var selectedUrl: String? = model.downloadUrl
        var selectedFilename: String? = model.filename

        // ✅ Nếu đã download rồi thì không cho đổi URL
        if (model.isDownloaded) {
            dialogBinding.etModelUrl.setText(model.downloadUrl)
            dialogBinding.etModelUrl.isEnabled = false
            dialogBinding.btnCheckRepo.visibility = View.GONE
            dialogBinding.tvSelectedFile.visibility = View.VISIBLE
            dialogBinding.tvSelectedFile.text = "✅ ${model.filename} (đã tải, không thể đổi)"
        } else {
            // ✅ Chưa download -> cho phép đổi version
            // Lấy repo URL từ download URL
            val repoUrl = model.downloadUrl.substringBefore("/resolve/")
            dialogBinding.etModelUrl.setText(repoUrl)

            var currentRepoUrl = repoUrl

            dialogBinding.btnCheckRepo.setOnClickListener {
                lifecycleScope.launch {
                    dialogBinding.btnCheckRepo.isEnabled = false
                    dialogBinding.btnCheckRepo.text = "Đang tìm..."
                    dialogBinding.progressChecking.visibility = View.VISIBLE

                    val ggufList = modelManager.getGGUFList(currentRepoUrl)

                    dialogBinding.progressChecking.visibility = View.GONE
                    dialogBinding.btnCheckRepo.isEnabled = true
                    dialogBinding.btnCheckRepo.text = "🔍 Kiểm tra repo"

                    if (ggufList.isEmpty()) {
                        Toast.makeText(requireContext(), "Không tìm thấy file GGUF", Toast.LENGTH_SHORT).show()
                    } else {
                        dialogBinding.layoutFileList.visibility = View.VISIBLE
                        dialogBinding.layoutSelectedFile.visibility = View.GONE
                        dialogBinding.rvFileList.layoutManager = LinearLayoutManager(requireContext())
                        dialogBinding.rvFileList.adapter = GGUFAdapter(ggufList) { selectedFile ->
                            selectedUrl = "${currentRepoUrl.trimEnd('/')}/resolve/main/${selectedFile.filename}"
                            selectedFilename = selectedFile.filename
                            dialogBinding.tvSelectedFile.visibility = View.VISIBLE
                            dialogBinding.tvSelectedFile.text = "✅ ${selectedFile.filename} (~${selectedFile.sizeMB}MB)"
                            dialogBinding.layoutFileList.visibility = View.GONE
                        }
                    }
                }
            }

            // Nút x để chọn lại
            dialogBinding.btnClearSelection.setOnClickListener {
                selectedUrl = null
                selectedFilename = null
                dialogBinding.layoutSelectedFile.visibility = View.GONE
                dialogBinding.layoutFileList.visibility = View.VISIBLE
            }
        }

        dialogBinding.btnAdd.text = "Lưu thay đổi"
        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnAdd.setOnClickListener {
            lifecycleScope.launch {
                val newDesc = dialogBinding.etModelDesc.text.toString().trim()
                val finalUrl = selectedUrl ?: model.downloadUrl
                val finalFilename = selectedFilename ?: model.filename

                modelManager.updateCustomModel(
                    modelId = model.id,
                    name = model.name,
                    description = newDesc,
                    downloadUrl = finalUrl,
                    filename = finalFilename
                )

                viewModel.loadModels()
                Toast.makeText(requireContext(), "✅ Đã cập nhật!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun onDownloadClicked(model: ModelManager.ModelInfo) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.downloadModel(model.id)

        }
    }


    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            val pausedStatesCache = mutableMapOf<String, DownloadState>()

            combine(
                viewModel.models,
                viewModel.downloadStates,
                viewModel.downloadingIds,
                viewModel.pausedIds,
                viewModel.activeModelId
            ) { models, downloadStates, downloading, paused, activeId ->
                models.map { model ->
                    val isDownloading = downloading.contains(model.id)
                    val isPaused      = paused.contains(model.id)

                    val ds = when {
                        isDownloading -> {
                            downloadStates[model.id]?.also { pausedStatesCache[model.id] = it }
                                ?: DownloadState()
                        }
                        isPaused -> {
                            // Lấy từ cache để hiển thị progress đã tải
                            pausedStatesCache[model.id]?.copy(speed = "Đã tạm dừng")
                                ?: DownloadState(speed = "Đã tạm dừng")
                        }
                        else -> DownloadState()
                    }

                    ModelAdapter.ModelState(
                        info          = model.copy(isDownloaded = model.isDownloaded && !isDownloading && !isPaused),
                        isDownloading = isDownloading,
                        isPaused      = isPaused,
                        progress      = ds.progress,
                        speed         = ds.speed,
                        downloadedBytes = ds.downloadedBytes,
                        totalBytes    = ds.totalBytes,
                        isActive      = model.id == activeId
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
    private fun showAddCustomModelDialog() {
        var selectedName: String? = null
        var selectedUrl: String? = null
        var currentRepoUrl: String? = null  // ✅ Lưu repo URL để hiện lại list

        val dialogBinding = DialogAddCustomModelBinding.inflate(LayoutInflater.from(requireContext()))

        val deviceInfo = modelValidator.getDeviceInfo()
        dialogBinding.tvDeviceInfo.text = "📱 RAM: ${deviceInfo["ramTotal"]}MB | Android ${deviceInfo["androidVersion"]}"

        val dialog = android.app.Dialog(requireContext(), R.style.DialogTheme)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.layoutFileList.visibility = View.GONE
        dialogBinding.layoutSelectedFile.visibility = View.GONE

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }

        // ✅ Nút x - hiện lại danh sách version
        dialogBinding.btnClearSelection.setOnClickListener {
            selectedName = null
            selectedUrl = null
            dialogBinding.layoutSelectedFile.visibility = View.GONE
            // Hiện lại danh sách nếu có repo URL
            currentRepoUrl?.let { repoUrl ->
                lifecycleScope.launch {
                    dialogBinding.layoutFileList.visibility = View.VISIBLE
                    val ggufList = modelManager.getGGUFList(repoUrl)
                    dialogBinding.rvFileList.adapter = GGUFAdapter(ggufList) { file ->
                        selectedName = file.filename.removeSuffix(".gguf")
                        selectedUrl = "${repoUrl.trimEnd('/')}/resolve/main/${file.filename}"
                        dialogBinding.tvSelectedFile.text = "✅ $selectedName (~${file.sizeMB}MB)"
                        dialogBinding.layoutSelectedFile.visibility = View.VISIBLE
                        dialogBinding.layoutFileList.visibility = View.GONE
                    }
                }
            }
        }

        dialogBinding.btnCheckRepo.setOnClickListener {
            val url = dialogBinding.etModelUrl.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener

            currentRepoUrl = url  // ✅ Lưu repo URL

            lifecycleScope.launch {
                dialogBinding.btnCheckRepo.isEnabled = false
                dialogBinding.btnCheckRepo.text = "Đang tìm..."
                dialogBinding.progressChecking.visibility = View.VISIBLE

                val ggufList = modelManager.getGGUFList(url)

                dialogBinding.progressChecking.visibility = View.GONE
                dialogBinding.btnCheckRepo.isEnabled = true
                dialogBinding.btnCheckRepo.text = "🔍 Kiểm tra repo"

                if (ggufList.isEmpty()) {
                    Toast.makeText(requireContext(), "Không tìm thấy file GGUF", Toast.LENGTH_SHORT).show()
                } else {
                    dialogBinding.layoutFileList.visibility = View.VISIBLE
                    dialogBinding.layoutSelectedFile.visibility = View.GONE  // Ẩn file cũ
                    dialogBinding.rvFileList.layoutManager = LinearLayoutManager(requireContext())
                    dialogBinding.rvFileList.adapter = GGUFAdapter(ggufList) { selectedFile ->
                        selectedName = selectedFile.filename.removeSuffix(".gguf")
                        selectedUrl = "${url.trimEnd('/')}/resolve/main/${selectedFile.filename}"
                        dialogBinding.tvSelectedFile.text = "✅ $selectedName (~${selectedFile.sizeMB}MB)"
                        dialogBinding.layoutSelectedFile.visibility = View.VISIBLE
                        dialogBinding.layoutFileList.visibility = View.GONE
                    }
                }
            }
        }

        dialogBinding.btnAdd.setOnClickListener {
            val name = selectedName
            val url = selectedUrl
            val desc = dialogBinding.etModelDesc.text.toString().trim()  // ✅ Lấy mô tả

            if (name == null || url == null) {
                val rawUrl = dialogBinding.etModelUrl.text.toString().trim()
                if (rawUrl.isEmpty()) {
                    Toast.makeText(requireContext(), "Chọn file GGUF trước", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val filename = rawUrl.substringAfterLast("/").substringBefore("?")
                val fallbackName = filename.removeSuffix(".gguf")
                addModel(fallbackName, rawUrl, filename, desc, dialogBinding, dialog)  // ✅ Truyền desc
                return@setOnClickListener
            }

            val filename = url.substringAfterLast("/").substringBefore("?")
            addModel(name, url, filename, desc, dialogBinding, dialog)  // ✅ Truyền desc
        }

        dialog.show()
    }

    // ✅ Thêm tham số desc
    private fun addModel(
        name: String,
        url: String,
        filename: String,
        desc: String,
        dialogBinding: DialogAddCustomModelBinding,
        dialog: android.app.Dialog
    ) {
        lifecycleScope.launch {
            dialogBinding.btnAdd.isEnabled = false
            dialogBinding.btnAdd.text = "Đang kiểm tra..."

            val result = modelManager.addCustomModel(name, desc, url, filename)  // ✅ Truyền desc

            result.onSuccess {
                Toast.makeText(requireContext(), "✅ Đã thêm $name!", Toast.LENGTH_SHORT).show()
                viewModel.loadModels()
                dialog.dismiss()
            }.onFailure { e ->
                Toast.makeText(requireContext(), "❌ ${e.message}", Toast.LENGTH_SHORT).show()
            }

            dialogBinding.btnAdd.isEnabled = true
            dialogBinding.btnAdd.text = "Thêm model"
        }
    }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


}