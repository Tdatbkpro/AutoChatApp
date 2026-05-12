package com.example.autochat.ui.phone.viewmodel

// ═══════════════════════════════════════════════════════════════════════════════
// PATCH CHO ModelViewModel — thêm ONNX download support
// Merge vào file ModelViewModel.kt hiện tại
// ═══════════════════════════════════════════════════════════════════════════════

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autochat.llm.LlmEngineFactory
import com.example.autochat.llm.ModelManager
import com.example.autochat.llm.ModelType
import com.example.autochat.domain.repository.GeminiKeyRepository
import com.example.autochat.remote.dto.response.GeminiKeyStatusResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

// ── State cho ONNX file selection ─────────────────────────────────────────────

data class OnnxAddState(
    val isLoading: Boolean = false,
    val files: List<ModelManager.OnnxFileInfo> = emptyList(),
    val selectedFiles: Set<String> = emptySet(),
    val error: String? = null,
)
data class DownloadState(        // ← thêm vào đây
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speed: String = "0 B/s"
)

@HiltViewModel
class ModelViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val engineFactory: LlmEngineFactory,
    private val geminiKeyRepository: GeminiKeyRepository,
) : ViewModel() {

    // ── Existing state (giữ nguyên) ───────────────────────────────────────────

    private val _models = MutableStateFlow<List<ModelManager.ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelManager.ModelInfo>> = _models.asStateFlow()

    private val _statusText = MutableStateFlow("Chưa có model")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    private val _pausedIds = MutableStateFlow<Set<String>>(emptySet())
    val pausedIds: StateFlow<Set<String>> = _pausedIds.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()

    // ── NEW: ONNX add state ───────────────────────────────────────────────────

    private val _onnxAddState = MutableStateFlow(OnnxAddState())
    val onnxAddState: StateFlow<OnnxAddState> = _onnxAddState.asStateFlow()

    // ── Gemini key state (giữ nguyên) ─────────────────────────────────────────

    private val _geminiKeyStatus = MutableStateFlow<GeminiKeyStatusResponse?>(null)
    val geminiKeyStatus: StateFlow<GeminiKeyStatusResponse?> = _geminiKeyStatus.asStateFlow()

    private val _geminiKeyLoading = MutableStateFlow(false)
    val geminiKeyLoading: StateFlow<Boolean> = _geminiKeyLoading.asStateFlow()

    private val _geminiKeyError = MutableStateFlow<String?>(null)
    val geminiKeyError: StateFlow<String?> = _geminiKeyError.asStateFlow()

    // ── Gemini actions (giữ nguyên) ───────────────────────────────────────────

    fun fetchGeminiKeyStatus() {
        viewModelScope.launch {
            geminiKeyRepository.getStatus()
                .onSuccess { _geminiKeyStatus.value = it }
                .onFailure { _geminiKeyError.value = "Không lấy được trạng thái key" }
        }
    }

    fun saveGeminiKey(apiKey: String) {
        viewModelScope.launch {
            _geminiKeyLoading.value = true
            geminiKeyRepository.saveKey(apiKey)
                .onSuccess {
                    _geminiKeyStatus.value = GeminiKeyStatusResponse(hasKey = true, maskedKey = null)
                    fetchGeminiKeyStatus()
                }
                .onFailure { _geminiKeyError.value = "Lưu key thất bại: ${it.message}" }
            _geminiKeyLoading.value = false
        }
    }

    fun deleteGeminiKey() {
        viewModelScope.launch {
            _geminiKeyLoading.value = true
            geminiKeyRepository.deleteKey()
                .onSuccess { _geminiKeyStatus.value = GeminiKeyStatusResponse(hasKey = false, maskedKey = null) }
                .onFailure { _geminiKeyError.value = "Xóa key thất bại: ${it.message}" }
            _geminiKeyLoading.value = false
        }
    }

    fun clearGeminiKeyError() { _geminiKeyError.value = null }

    // ── Model management ──────────────────────────────────────────────────────

    fun loadModels() {
        viewModelScope.launch {
            _models.value = modelManager.getAllModels()
            updateStatus()
        }
    }

    // ── ONNX: fetch file list từ repo ─────────────────────────────────────────

    fun fetchOnnxFiles(repoUrl: String) {
        viewModelScope.launch {
            _onnxAddState.value = OnnxAddState(isLoading = true)
            val files = modelManager.getOnnxFileList(repoUrl)
            if (files.isEmpty()) {
                _onnxAddState.value = OnnxAddState(
                    error = "Không tìm thấy file ONNX trong repo này"
                )
            } else {
                // Mặc định chọn các file bắt buộc
                val defaultSelected = files.filter { it.required }.map { it.filename }.toSet()
                _onnxAddState.value = OnnxAddState(
                    files         = files,
                    selectedFiles = defaultSelected,
                )
            }
        }
    }

    fun toggleOnnxFileSelection(filename: String) {
        val current = _onnxAddState.value
        val newSelected = if (filename in current.selectedFiles)
            current.selectedFiles - filename
        else
            current.selectedFiles + filename
        _onnxAddState.value = current.copy(selectedFiles = newSelected)
    }

    fun clearOnnxAddState() {
        _onnxAddState.value = OnnxAddState()
    }

    // ── ONNX: thêm model vào DB ───────────────────────────────────────────────

    fun addOnnxModel(
        name: String,
        description: String,
        repoUrl: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val selectedFiles = _onnxAddState.value.selectedFiles.toList()
        viewModelScope.launch {
            modelManager.addCustomOnnxModel(
                name           = name,
                description    = description,
                repoUrl        = repoUrl,
                estimatedSizeMB = _onnxAddState.value.files
                    .filter { it.filename in _onnxAddState.value.selectedFiles }
                    .sumOf { it.sizeMB },
                selectedFiles = selectedFiles,
            ).onSuccess {
                loadModels()
                clearOnnxAddState()
                onSuccess()
            }.onFailure {
                onError(it.message ?: "Thêm model thất bại")
            }
        }
    }

    // ── Download — phân biệt GGUF vs ONNX ────────────────────────────────────

    fun downloadModel(modelId: String) {
        if (modelManager.isOnnxModel(modelId)) {
            startOnnxDownload(modelId)
        } else {
            startDownload(modelId, resume = false)
        }
    }

    fun resumeDownload(modelId: String) {
        if (modelManager.isOnnxModel(modelId)) {
            startOnnxDownload(modelId)   // ONNX tự skip file đã tải
        } else {
            startDownload(modelId, resume = true)
        }
    }

    // ── ONNX download ─────────────────────────────────────────────────────────

    private fun startOnnxDownload(modelId: String) {
        downloadJobs[modelId]?.cancel()

        val modelInfo = _models.value.find { it.id == modelId }
        if (modelInfo == null) {
            android.util.Log.e("ModelVM", "startOnnxDownload: model not found $modelId")
            return
        }

        val baseUrl = modelInfo.downloadUrl
        android.util.Log.d("ModelVM", "ONNX download start: $modelId, baseUrl=$baseUrl")



        _downloadingIds.value = _downloadingIds.value + modelId
        _downloadStates.value = _downloadStates.value + (modelId to DownloadState())

        val job = viewModelScope.launch {
            val filesToDownload = modelManager.getOnnxFilesToDownload(modelId)
            android.util.Log.d("ModelVM", "Files to download: $filesToDownload")

            modelManager.downloadOnnxModel(
                modelId      = modelId,
                baseUrl      = baseUrl,
                files        = filesToDownload,
                onTotalBytes = { total ->
                    val prev = _downloadStates.value[modelId] ?: DownloadState()
                    _downloadStates.value = _downloadStates.value + (modelId to prev.copy(
                        totalBytes = total
                    ))
                },
                onProgress   = { progress, downloaded ->
                    val prev = _downloadStates.value[modelId] ?: DownloadState()
                    _downloadStates.value = _downloadStates.value + (modelId to prev.copy(
                        progress        = progress,
                        downloadedBytes = downloaded,
                        speed           = "Đang tải ONNX..."
                    ))
                }
            ).onSuccess {
                android.util.Log.d("ModelVM", "ONNX download success: $modelId")
                _downloadingIds.value = _downloadingIds.value - modelId
                _downloadStates.value = _downloadStates.value - modelId
                loadModels()
                updateStatus()
            }.onFailure { e ->
                android.util.Log.e("ModelVM", "ONNX download failed: ${e.message}")
                if (e !is CancellationException) {
                    _downloadingIds.value = _downloadingIds.value - modelId
                    _downloadStates.value = _downloadStates.value - modelId
                    loadModels()
                }
            }
        }
        downloadJobs[modelId] = job
    }

    // ── GGUF download (giữ nguyên) ────────────────────────────────────────────

    private fun startDownload(modelId: String, resume: Boolean) {
        modelManager.signalCancel(modelId)
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)

        _downloadingIds.value = _downloadingIds.value - modelId
        _pausedIds.value      = _pausedIds.value - modelId

        val fallbackTotal = _models.value.find { it.id == modelId }
            ?.sizeMB?.times(1024L * 1024L) ?: 0L
        val startBytes = if (resume) modelManager.getPartialSize(modelId) else 0L

        _downloadingIds.value = _downloadingIds.value + modelId
        _downloadStates.value = _downloadStates.value + (modelId to DownloadState(
            totalBytes      = fallbackTotal,
            downloadedBytes = startBytes,
            progress        = if (fallbackTotal > 0) startBytes.toFloat() / fallbackTotal else 0f
        ))

        if (!resume) modelManager.deletePartialDownload(modelId)

        var lastBytes = startBytes
        var lastTime  = System.currentTimeMillis()

        val job = viewModelScope.launch {
            modelManager.downloadModel(
                modelId      = modelId,
                resume       = resume,
                onTotalBytes = { realTotal ->
                    val prev = _downloadStates.value[modelId] ?: DownloadState()
                    _downloadStates.value = _downloadStates.value + (modelId to prev.copy(
                        totalBytes = realTotal,
                        progress   = if (realTotal > 0) prev.downloadedBytes.toFloat() / realTotal else 0f
                    ))
                },
                onProgress   = { progress, downloadedBytes ->
                    val now     = System.currentTimeMillis()
                    val elapsed = (now - lastTime) / 1000f
                    val diff    = downloadedBytes - lastBytes
                    val speed   = if (elapsed > 0 && diff > 0) {
                        lastBytes = downloadedBytes; lastTime = now
                        formatSpeed(diff / elapsed)
                    } else _downloadStates.value[modelId]?.speed ?: "0 B/s"
                    val prev = _downloadStates.value[modelId] ?: DownloadState()
                    _downloadStates.value = _downloadStates.value + (modelId to prev.copy(
                        progress        = if (progress >= 0f) progress else prev.progress,
                        downloadedBytes = downloadedBytes,
                        speed           = speed
                    ))
                }
            ).onSuccess {
                _downloadingIds.value = _downloadingIds.value - modelId
                _downloadStates.value = _downloadStates.value - modelId
                loadModels(); updateStatus()
            }.onFailure { e ->
                if (e !is CancellationException) {
                    _downloadingIds.value = _downloadingIds.value - modelId
                    _downloadStates.value = _downloadStates.value - modelId
                    loadModels()
                }
            }
        }
        downloadJobs[modelId] = job
    }

    // ── Pause / Cancel (GGUF only, ONNX không hỗ trợ pause) ──────────────────

    fun pauseDownload(modelId: String) {
        if (modelManager.isOnnxModel(modelId)) return  // ONNX không pause
        modelManager.signalPause(modelId)
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        val currentState = _downloadStates.value[modelId]
        _downloadingIds.value = _downloadingIds.value - modelId
        _pausedIds.value      = _pausedIds.value + modelId
        if (currentState != null) {
            _downloadStates.value = _downloadStates.value + (modelId to currentState.copy(
                speed = "Đã tạm dừng"
            ))
        }
    }

    fun cancelDownload(modelId: String) {
        modelManager.signalCancel(modelId)
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        _downloadingIds.value = _downloadingIds.value - modelId
        _pausedIds.value      = _pausedIds.value - modelId
        _downloadStates.value = _downloadStates.value - modelId
        if (!modelManager.isOnnxModel(modelId)) {
            modelManager.deletePartialDownload(modelId)
        }
        _models.value = _models.value.map { model ->
            if (model.id == modelId) model.copy(isDownloaded = false, downloadedSizeMB = 0)
            else model
        }
        updateStatus()
    }

    // ── Select model ──────────────────────────────────────────────────────────

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            _statusText.value = "Đang load model..."
            try {
                engineFactory.loadAndGet(modelId)
                _activeModelId.value = modelId
                updateStatus()
                loadModels()
            } catch (e: Exception) {
                _statusText.value = "Lỗi load: ${e.message}"
            }
        }
    }

    fun checkOnlineStatus(isOnline: Boolean) { _isOnline.value = isOnline }

    // ── Delete model ──────────────────────────────────────────────────────────

    fun deleteModel(modelId: String) {
        modelManager.signalCancel(modelId)
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        _downloadingIds.value = _downloadingIds.value - modelId
        _pausedIds.value      = _pausedIds.value - modelId
        _downloadStates.value = _downloadStates.value - modelId

        val activeEngine = engineFactory.getActiveEngine()
        if (activeEngine?.getCurrentModelId() == modelId) {
            activeEngine.unloadModel()
            _activeModelId.value = null
        }

        if (modelId.startsWith("custom_")) {
            viewModelScope.launch {
                modelManager.deleteCustomModel(modelId)
                loadModels(); updateStatus()
            }
        } else {
            modelManager.deleteModel(modelId)
            loadModels(); updateStatus()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateStatus() {
        val loadedModelId = engineFactory.getActiveEngine()?.getCurrentModelId()
        if (loadedModelId != null) {
            val modelName = _models.value.find { it.id == loadedModelId }?.name ?: loadedModelId
            _statusText.value = "Đang dùng: $modelName"
        } else {
            val downloaded = _models.value.filter { it.isDownloaded }
            _statusText.value = when {
                downloaded.isNotEmpty() -> "Đã tải: ${downloaded.joinToString(", ") { it.name }}"
                else -> "Chưa có model nào được tải"
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Float): String = when {
        bytesPerSec <= 0       -> "0 B/s"
        bytesPerSec >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_000_000)
        bytesPerSec >= 1_000   -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1_000)
        else                   -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
    }

    override fun onCleared() {
        super.onCleared()
        downloadJobs.values.forEach { it.cancel() }
    }
}