package com.example.autochat.ui.phone.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.autochat.R
import com.example.autochat.databinding.DialogAddCustomModelBinding
import com.example.autochat.databinding.FragmentModelListBinding
import com.example.autochat.llm.ModelManager
import com.example.autochat.ui.phone.adapter.GGUFAdapter
import com.example.autochat.ui.phone.adapter.ModelAdapter
import com.example.autochat.ui.phone.viewmodel.DownloadState
import com.example.autochat.ui.phone.viewmodel.ModelViewModel
import com.example.autochat.ui.phone.viewmodel.OnnxAddState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ModelListFragment : Fragment() {

    private var _binding: FragmentModelListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ModelViewModel by activityViewModels()
    private lateinit var adapter: ModelAdapter

    @Inject lateinit var modelManager: ModelManager
    @Inject lateinit var modelValidator: com.example.autochat.llm.ModelValidator

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

        // Nút thêm GGUF (giữ nguyên hành vi cũ)
        binding.btnAddCustom.setOnClickListener {
            showModelDialog(startOnOnnxTab = false)
        }

        // Nút thêm ONNX (mở dialog ở tab ONNX)
        binding.btnAddOnnx.setOnClickListener {
            showModelDialog(startOnOnnxTab = true)
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private fun setupAdapter() {
        adapter = ModelAdapter(
            onDownload = { model -> viewModel.downloadModel(model.id) },
            onPause    = { model -> viewModel.pauseDownload(model.id) },
            onResume   = { model -> viewModel.resumeDownload(model.id) },
            onCancel   = { model -> viewModel.cancelDownload(model.id) },
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

    // ── Dialog dùng chung cho GGUF + ONNX ────────────────────────────────────

    private fun showModelDialog(startOnOnnxTab: Boolean) {
        val dialogBinding = DialogAddCustomModelBinding.inflate(
            LayoutInflater.from(requireContext())
        )

        val dialog = Dialog(requireContext(), R.style.DialogTheme)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // ── Tab switching ─────────────────────────────────────────────────────

        fun selectTab(onnx: Boolean) {
            if (onnx) {
                // ONNX tab active
                dialogBinding.tabOnnx.setTextColor(0xFFFFFFFF.toInt())
                dialogBinding.tabOnnx.setBackgroundResource(R.drawable.bg_tab_selected)
                dialogBinding.tabGguf.setTextColor(0xFF888899.toInt())
                dialogBinding.tabGguf.setBackgroundResource(R.drawable.bg_tab_unselected)
                dialogBinding.layoutGguf.visibility = View.GONE
                dialogBinding.layoutOnnx.visibility = View.VISIBLE
                dialogBinding.btnAdd.text = "Thêm ONNX"
                dialogBinding.btnAdd.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF5555AA.toInt())
            } else {
                // GGUF tab active
                dialogBinding.tabGguf.setTextColor(0xFFFFFFFF.toInt())
                dialogBinding.tabGguf.setBackgroundResource(R.drawable.bg_tab_selected)
                dialogBinding.tabOnnx.setTextColor(0xFF888899.toInt())
                dialogBinding.tabOnnx.setBackgroundResource(R.drawable.bg_tab_unselected)
                dialogBinding.layoutGguf.visibility = View.VISIBLE
                dialogBinding.layoutOnnx.visibility = View.GONE
                dialogBinding.btnAdd.text = "Thêm model"
                dialogBinding.btnAdd.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF6C63FF.toInt())
            }
        }

        // Khởi tạo tab ban đầu
        selectTab(startOnOnnxTab)

        dialogBinding.tabGguf.setOnClickListener { selectTab(false) }
        dialogBinding.tabOnnx.setOnClickListener { selectTab(true) }

        // ── GGUF logic (giữ nguyên từ showAddCustomModelDialog cũ) ───────────

        setupGgufSection(dialogBinding, dialog)

        // ── ONNX logic ────────────────────────────────────────────────────────

        setupOnnxSection(dialogBinding, dialog)

        // ── Nút Thêm — dispatch theo tab ─────────────────────────────────────

        dialogBinding.btnAdd.setOnClickListener {
            val isOnnxTab = dialogBinding.layoutOnnx.visibility == View.VISIBLE
            if (isOnnxTab) {
                handleAddOnnx(dialogBinding, dialog)
            } else {
                handleAddGguf(dialogBinding, dialog)
            }
        }

        dialogBinding.btnCancel.setOnClickListener {
            viewModel.clearOnnxAddState()
            dialog.dismiss()
        }

        dialog.setOnDismissListener { viewModel.clearOnnxAddState() }
        dialog.show()
    }

    // ── GGUF Section ──────────────────────────────────────────────────────────

    private fun setupGgufSection(
        b: DialogAddCustomModelBinding,
        dialog: Dialog
    ) {
        val deviceInfo = modelValidator.getDeviceInfo()
        b.tvDeviceInfo.text =
            "📱 RAM: ${deviceInfo["ramTotal"]}MB | Android ${deviceInfo["androidVersion"]}"

        var selectedName: String? = null
        var selectedUrl:  String? = null
        var currentRepoUrl: String? = null

        b.btnClearSelection.setOnClickListener {
            selectedName = null; selectedUrl = null
            b.layoutSelectedFile.visibility = View.GONE
            currentRepoUrl?.let { url ->
                lifecycleScope.launch {
                    b.layoutFileList.visibility = View.VISIBLE
                    val list = modelManager.getGGUFList(url)
                    b.rvFileList.adapter = GGUFAdapter(list) { file ->
                        selectedName = file.filename.removeSuffix(".gguf")
                        selectedUrl  = "${url.trimEnd('/')}/resolve/main/${file.filename}"
                        b.tvSelectedFile.text = "✅ $selectedName (~${file.sizeMB}MB)"
                        b.layoutSelectedFile.visibility = View.VISIBLE
                        b.layoutFileList.visibility     = View.GONE
                    }
                }
            }
        }

        b.btnCheckRepo.setOnClickListener {
            val url = b.etModelUrl.text?.toString()?.trim() ?: return@setOnClickListener
            if (url.isEmpty()) return@setOnClickListener
            currentRepoUrl = url
            lifecycleScope.launch {
                b.btnCheckRepo.isEnabled = false
                b.btnCheckRepo.text      = "Đang tìm..."
                b.progressChecking.visibility = View.VISIBLE
                val list = modelManager.getGGUFList(url)
                b.progressChecking.visibility = View.GONE
                b.btnCheckRepo.isEnabled = true
                b.btnCheckRepo.text      = "Kiểm tra repo"
                if (list.isEmpty()) {
                    Toast.makeText(requireContext(), "Không tìm thấy file GGUF", Toast.LENGTH_SHORT).show()
                } else {
                    b.layoutFileList.visibility     = View.VISIBLE
                    b.layoutSelectedFile.visibility = View.GONE
                    b.rvFileList.layoutManager = LinearLayoutManager(requireContext())
                    b.rvFileList.adapter = GGUFAdapter(list) { file ->
                        selectedName = file.filename.removeSuffix(".gguf")
                        selectedUrl  = "${url.trimEnd('/')}/resolve/main/${file.filename}"
                        b.tvSelectedFile.text = "✅ $selectedName (~${file.sizeMB}MB)"
                        b.layoutSelectedFile.visibility = View.VISIBLE
                        b.layoutFileList.visibility     = View.GONE
                    }
                }
            }
        }

        // Lưu selectedName/Url để handleAddGguf đọc được — dùng tag
        b.root.tag = object {
            var name: String? = null
            var url:  String? = null
        }
    }

    private fun handleAddGguf(b: DialogAddCustomModelBinding, dialog: Dialog) {
        // Đọc URL từ etModelUrl (selected file URL)
        val desc = b.etModelDesc.text?.toString()?.trim() ?: ""
        // selectedUrl được lưu trong tvSelectedFile — parse lại từ text hoặc etModelUrl
        val rawUrl = b.etModelUrl.text?.toString()?.trim() ?: ""

        // Nếu tvSelectedFile có text → đã chọn file cụ thể
        val selectedFileText = b.tvSelectedFile.text?.toString() ?: ""
        if (selectedFileText.isEmpty() && rawUrl.isEmpty()) {
            Toast.makeText(requireContext(), "Chọn file GGUF trước", Toast.LENGTH_SHORT).show()
            return
        }

        // Lấy URL từ etModelUrl (repo URL) + filename từ tvSelectedFile
        // Logic tương tự code cũ trong addModel()
        val finalUrl = if (selectedFileText.isNotEmpty()) {
            // URL đã được set khi click GGUFAdapter → lưu tạm vào tag
            b.etModelUrl.getTag(R.id.etModelUrl) as? String ?: rawUrl
        } else rawUrl

        val filename    = finalUrl.substringAfterLast("/").substringBefore("?")
        val fallbackName = filename.removeSuffix(".gguf")

        lifecycleScope.launch {
            b.btnAdd.isEnabled = false
            b.btnAdd.text      = "Đang kiểm tra..."
            val result = modelManager.addCustomModel(fallbackName, desc, finalUrl, filename)
            result.onSuccess {
                Toast.makeText(requireContext(), "✅ Đã thêm $fallbackName!", Toast.LENGTH_SHORT).show()
                viewModel.loadModels()
                dialog.dismiss()
            }.onFailure { e ->
                Toast.makeText(requireContext(), "❌ ${e.message}", Toast.LENGTH_SHORT).show()
            }
            b.btnAdd.isEnabled = true
            b.btnAdd.text      = "Thêm model"
        }
    }

    // ── ONNX Section ──────────────────────────────────────────────────────────

    private fun setupOnnxSection(b: DialogAddCustomModelBinding, dialog: Dialog) {
        // Observe ViewModel ONNX state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.onnxAddState.collectLatest { state ->
                applyOnnxState(b, state)
            }
        }

        b.btnCheckOnnxRepo.setOnClickListener {
            val url = b.etOnnxUrl.text?.toString()?.trim() ?: ""
            if (url.isEmpty()) {
                Toast.makeText(requireContext(), "Nhập URL repo trước", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.fetchOnnxFiles(url)
            // Auto-fill tên
            if (b.etOnnxName.text.isNullOrEmpty()) {
                b.etOnnxName.setText(url.trimEnd('/').substringAfterLast("/"))
            }
        }
    }

    private fun applyOnnxState(b: DialogAddCustomModelBinding, state: OnnxAddState) {
        when {
            state.isLoading -> {
                b.progressOnnxChecking.visibility = View.VISIBLE
                b.tvOnnxError.visibility          = View.GONE
                b.layoutOnnxFiles.visibility      = View.GONE
                b.tvOnnxFileHeader.visibility     = View.GONE
                b.tvOnnxTotalSize.visibility      = View.GONE
                b.btnCheckOnnxRepo.isEnabled      = false
            }
            state.error != null -> {
                b.progressOnnxChecking.visibility = View.GONE
                b.tvOnnxError.visibility          = View.VISIBLE
                b.tvOnnxError.text                = state.error
                b.btnCheckOnnxRepo.isEnabled      = true
            }
            state.files.isNotEmpty() -> {
                b.progressOnnxChecking.visibility = View.GONE
                b.tvOnnxError.visibility          = View.GONE
                b.tvOnnxFileHeader.visibility     = View.VISIBLE
                b.layoutOnnxFiles.visibility      = View.VISIBLE
                b.btnCheckOnnxRepo.isEnabled      = true

                // Rebuild checkbox list
                b.layoutOnnxFiles.removeAllViews()
                state.files.forEach { file ->
                    val cb = CheckBox(requireContext()).apply {
                        val sizeStr = when {
                            file.sizeMB > 0  -> " (~${file.sizeMB}MB)"
                            file.sizeKB > 0  -> " (~${file.sizeKB}KB)"
                            else             -> ""
                        }
                        val badge   = if (file.required) " ⚡" else ""
                        text        = "${file.filename}$sizeStr$badge"
                        textSize    = 12f
                        setTextColor(
                            if (file.required) 0xFFCCCCFF.toInt() else 0xFFAAAAAA.toInt()
                        )
                        isChecked = file.filename in state.selectedFiles
                        isEnabled = !file.required   // required luôn checked
                        setPadding(4, 6, 4, 6)
                        setOnCheckedChangeListener { _, _ ->
                            viewModel.toggleOnnxFileSelection(file.filename)
                        }
                    }
                    b.layoutOnnxFiles.addView(cb)
                }

                // Tổng dung lượng
                val totalMB = state.files
                    .filter { it.filename in state.selectedFiles }
                    .sumOf { it.sizeMB }
                b.tvOnnxTotalSize.visibility = View.VISIBLE
                b.tvOnnxTotalSize.text       = "Tổng cần tải: ~${totalMB} MB"

                // Enable nút thêm khi có ít nhất 1 file .onnx được chọn
                val hasOnnx = state.selectedFiles.any { it.endsWith(".onnx") }
                b.btnAdd.isEnabled = hasOnnx
            }
            else -> {
                b.progressOnnxChecking.visibility = View.GONE
                b.layoutOnnxFiles.visibility      = View.GONE
                b.tvOnnxFileHeader.visibility     = View.GONE
                b.tvOnnxTotalSize.visibility      = View.GONE
                b.btnCheckOnnxRepo.isEnabled      = true
                // Chỉ enable khi tab ONNX active VÀ đã fetch file
                if (b.layoutOnnx.visibility == View.VISIBLE) {
                    b.btnAdd.isEnabled = false
                }
            }
        }
    }

    private fun handleAddOnnx(b: DialogAddCustomModelBinding, dialog: Dialog) {
        val url  = b.etOnnxUrl.text?.toString()?.trim() ?: ""
        val name = b.etOnnxName.text?.toString()?.trim()
            .takeIf { !it.isNullOrEmpty() }
            ?: url.trimEnd('/').substringAfterLast("/")
        val desc = b.etOnnxDesc.text?.toString()?.trim() ?: ""

        if (url.isEmpty()) {
            Toast.makeText(requireContext(), "Nhập URL repo trước", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedFiles = viewModel.onnxAddState.value.selectedFiles
        if (selectedFiles.none { it.endsWith(".onnx") }) {
            Toast.makeText(requireContext(), "Cần chọn ít nhất 1 file .onnx", Toast.LENGTH_SHORT).show()
            return
        }

        b.btnAdd.isEnabled = false
        b.btnAdd.text      = "Đang thêm..."

        viewModel.addOnnxModel(
            name        = name,
            description = desc,
            repoUrl     = url,
            onSuccess   = {
                Toast.makeText(requireContext(), "✅ Đã thêm ONNX model: $name", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            },
            onError = { err ->
                Toast.makeText(requireContext(), "❌ $err", Toast.LENGTH_SHORT).show()
                b.btnAdd.isEnabled = true
                b.btnAdd.text      = "Thêm ONNX"
            }
        )
    }

    // ── Edit custom model (giữ nguyên) ────────────────────────────────────────

    private fun showEditCustomModelDialog(model: ModelManager.ModelInfo) {
        val dialogBinding = DialogAddCustomModelBinding.inflate(LayoutInflater.from(requireContext()))
        dialogBinding.etModelDesc.setText(model.description)
        dialogBinding.btnCheckRepo.visibility = View.VISIBLE

        val dialog = Dialog(requireContext(), R.style.DialogTheme)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Ẩn tab khi edit (chỉ GGUF edit được, ONNX edit chưa hỗ trợ)
        dialogBinding.tabGguf.visibility = View.GONE
        dialogBinding.tabOnnx.visibility = View.GONE
        dialogBinding.layoutGguf.visibility = View.VISIBLE
        dialogBinding.layoutOnnx.visibility = View.GONE

        var selectedUrl:      String? = model.downloadUrl
        var selectedFilename: String? = model.filename

        if (model.isDownloaded) {
            dialogBinding.etModelUrl.setText(model.downloadUrl)
            dialogBinding.etModelUrl.isEnabled    = false
            dialogBinding.btnCheckRepo.visibility = View.GONE
            dialogBinding.tvSelectedFile.visibility = View.VISIBLE
            dialogBinding.tvSelectedFile.text = "✅ ${model.filename} (đã tải, không thể đổi)"
        } else {
            val repoUrl = model.downloadUrl.substringBefore("/resolve/")
            dialogBinding.etModelUrl.setText(repoUrl)
            var currentRepoUrl = repoUrl

            dialogBinding.btnCheckRepo.setOnClickListener {
                lifecycleScope.launch {
                    dialogBinding.btnCheckRepo.isEnabled       = false
                    dialogBinding.btnCheckRepo.text            = "Đang tìm..."
                    dialogBinding.progressChecking.visibility  = View.VISIBLE
                    val list = modelManager.getGGUFList(currentRepoUrl)
                    dialogBinding.progressChecking.visibility  = View.GONE
                    dialogBinding.btnCheckRepo.isEnabled       = true
                    dialogBinding.btnCheckRepo.text            = "🔍 Kiểm tra repo"
                    if (list.isEmpty()) {
                        Toast.makeText(requireContext(), "Không tìm thấy file GGUF", Toast.LENGTH_SHORT).show()
                    } else {
                        dialogBinding.layoutFileList.visibility     = View.VISIBLE
                        dialogBinding.layoutSelectedFile.visibility = View.GONE
                        dialogBinding.rvFileList.layoutManager = LinearLayoutManager(requireContext())
                        dialogBinding.rvFileList.adapter = GGUFAdapter(list) { file ->
                            selectedUrl      = "${currentRepoUrl.trimEnd('/')}/resolve/main/${file.filename}"
                            selectedFilename = file.filename
                            dialogBinding.tvSelectedFile.visibility = View.VISIBLE
                            dialogBinding.tvSelectedFile.text = "✅ ${file.filename} (~${file.sizeMB}MB)"
                            dialogBinding.layoutFileList.visibility = View.GONE
                        }
                    }
                }
            }
            dialogBinding.btnClearSelection.setOnClickListener {
                selectedUrl = null; selectedFilename = null
                dialogBinding.layoutSelectedFile.visibility = View.GONE
                dialogBinding.layoutFileList.visibility     = View.VISIBLE
            }
        }

        dialogBinding.btnAdd.text = "Lưu thay đổi"
        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnAdd.setOnClickListener {
            lifecycleScope.launch {
                val newDesc      = dialogBinding.etModelDesc.text.toString().trim()
                val finalUrl     = selectedUrl ?: model.downloadUrl
                val finalFilename = selectedFilename ?: model.filename
                modelManager.updateCustomModel(
                    modelId     = model.id,
                    name        = model.name,
                    description = newDesc,
                    downloadUrl = finalUrl,
                    filename    = finalFilename
                )
                viewModel.loadModels()
                Toast.makeText(requireContext(), "✅ Đã cập nhật!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // ── Observe ───────────────────────────────────────────────────────────────

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
                            downloadStates[model.id]
                                ?.also { pausedStatesCache[model.id] = it }
                                ?: DownloadState()
                        }
                        isPaused -> pausedStatesCache[model.id]
                            ?.copy(speed = "Đã tạm dừng")
                            ?: DownloadState(speed = "Đã tạm dừng")
                        else -> DownloadState()
                    }
                    ModelAdapter.ModelState(
                        info            = model.copy(
                            isDownloaded = model.isDownloaded && !isDownloading && !isPaused
                        ),
                        isDownloading   = isDownloading,
                        isPaused        = isPaused && !modelManager.isOnnxModel(model.id),
                        progress        = ds.progress,
                        speed           = ds.speed,
                        downloadedBytes = ds.downloadedBytes,
                        totalBytes      = ds.totalBytes,
                        isActive        = model.id == activeId,
                        modelType       = model.modelType,
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

    // ── Storage ───────────────────────────────────────────────────────────────

    private fun showStorageDetails() {
        binding.tvStorageInfo.setOnClickListener {
            val info = modelManager.getStorageInfo()
            Toast.makeText(
                requireContext(),
                "Model đã tải: ${info.modelCount}\nDùng: ${info.usedMB}MB\nTrống: ${info.freeMB}MB",
                Toast.LENGTH_LONG
            ).show()
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