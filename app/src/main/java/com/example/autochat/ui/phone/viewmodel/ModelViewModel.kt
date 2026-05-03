package com.example.autochat.ui.phone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autochat.llm.LlmEngine
import com.example.autochat.llm.ModelManager
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

data class DownloadState(
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speed: String = "0 B/s"
)

@HiltViewModel
class ModelViewModel @Inject constructor(
    private val modelManager: ModelManager,
    private val llmEngine: LlmEngine,
    private val geminiKeyRepository: GeminiKeyRepository  // ✅ thay HFTokenManager
) : ViewModel() {

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

    // ── Gemini Key state ───────────────────────────────────────────────────────

    private val _geminiKeyStatus = MutableStateFlow<GeminiKeyStatusResponse?>(null)
    val geminiKeyStatus: StateFlow<GeminiKeyStatusResponse?> = _geminiKeyStatus.asStateFlow()

    private val _geminiKeyLoading = MutableStateFlow(false)
    val geminiKeyLoading: StateFlow<Boolean> = _geminiKeyLoading.asStateFlow()

    private val _geminiKeyError = MutableStateFlow<String?>(null)
    val geminiKeyError: StateFlow<String?> = _geminiKeyError.asStateFlow()

    // ── Gemini Key actions ─────────────────────────────────────────────────────

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
                    fetchGeminiKeyStatus()   // fetch lại để lấy masked_key từ server
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

    fun clearGeminiKeyError() {
        _geminiKeyError.value = null
    }

    // ── Model management (giữ nguyên) ─────────────────────────────────────────

    fun loadModels() {
        viewModelScope.launch {
            _models.value = modelManager.getAllModels()  // ✅ Lấy cả built-in + custom
            updateStatus()
        }
    }

    fun downloadModel(modelId: String) {
        startDownload(modelId, resume = false)
    }

    fun resumeDownload(modelId: String) {
        startDownload(modelId, resume = true)
    }

    private fun startDownload(modelId: String, resume: Boolean) {
        modelManager.signalCancel(modelId)
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)

        _downloadingIds.value = _downloadingIds.value - modelId
        _pausedIds.value = _pausedIds.value - modelId

        val fallbackTotal = _models.value.find { it.id == modelId }
            ?.sizeMB?.times(1024L * 1024L) ?: 0L

        val startBytes = if (resume) modelManager.getPartialSize(modelId) else 0L

        _downloadingIds.value = _downloadingIds.value + modelId
        _downloadStates.value = _downloadStates.value + (modelId to DownloadState(
            totalBytes = fallbackTotal,
            downloadedBytes = startBytes,
            progress = if (fallbackTotal > 0) startBytes.toFloat() / fallbackTotal else 0f
        ))

        if (!resume) modelManager.deletePartialDownload(modelId)

        var lastBytes = startBytes
        var lastTime = System.currentTimeMillis()

        val job = viewModelScope.launch {
            modelManager.downloadModel(
                modelId = modelId,
                resume = resume,
                onTotalBytes = { realTotal ->
                    val prev = _downloadStates.value[modelId] ?: DownloadState()
                    _downloadStates.value = _downloadStates.value + (modelId to prev.copy(
                        totalBytes = realTotal,
                        progress = if (realTotal > 0) prev.downloadedBytes.toFloat() / realTotal else 0f
                    ))
                },
                onProgress = { progress, downloadedBytes ->
                    val now = System.currentTimeMillis()
                    val elapsed = (now - lastTime) / 1000f
                    val diff = downloadedBytes - lastBytes
                    val speed = if (elapsed > 0 && diff > 0) {
                        lastBytes = downloadedBytes
                        lastTime = now
                        formatSpeed(diff / elapsed)
                    } else {
                        _downloadStates.value[modelId]?.speed ?: "0 B/s"
                    }
                    val prev = _downloadStates.value[modelId] ?: DownloadState()
                    _downloadStates.value = _downloadStates.value + (modelId to prev.copy(
                        progress = if (progress >= 0f) progress else prev.progress,
                        downloadedBytes = downloadedBytes,
                        speed = speed
                    ))
                }
            ).onSuccess {
                _downloadingIds.value = _downloadingIds.value - modelId
                _downloadStates.value = _downloadStates.value - modelId
                loadModels()
                updateStatus()
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

    fun pauseDownload(modelId: String) {
        modelManager.signalPause(modelId)
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)

        val currentState = _downloadStates.value[modelId]
        _downloadingIds.value = _downloadingIds.value - modelId
        _pausedIds.value = _pausedIds.value + modelId

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
        _pausedIds.value = _pausedIds.value - modelId
        _downloadStates.value = _downloadStates.value - modelId

        modelManager.deletePartialDownload(modelId)

        _models.value = _models.value.map { model ->
            if (model.id == modelId) model.copy(isDownloaded = false, downloadedSizeMB = 0)
            else model
        }
        updateStatus()
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            _statusText.value = "Đang load model..."
            llmEngine.loadModel(modelId).onSuccess {
                _activeModelId.value = modelId
                updateStatus()
                loadModels()
            }.onFailure { e ->
                _statusText.value = "Lỗi: ${e.message}"
            }
        }
    }

    fun checkOnlineStatus(isOnline: Boolean) {
        _isOnline.value = isOnline
    }

    fun deleteModel(modelId: String) {
        modelManager.signalCancel(modelId)
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        _downloadingIds.value = _downloadingIds.value - modelId
        _pausedIds.value = _pausedIds.value - modelId
        _downloadStates.value = _downloadStates.value - modelId

        if (llmEngine.getCurrentModelId() == modelId) {
            llmEngine.unloadModel()
            _activeModelId.value = null
        }

        // ✅ Phân biệt custom vs built-in
        if (modelId.startsWith("custom_")) {
            viewModelScope.launch {
                modelManager.deleteCustomModel(modelId)
                loadModels()
                updateStatus()
            }
        } else {
            modelManager.deleteModel(modelId)
            loadModels()
            updateStatus()
        }
    }

    private fun updateStatus() {
        val loadedModelId = llmEngine.getCurrentModelId()

        if (loadedModelId != null) {
            // ✅ Tìm trong cả built-in và custom
            val allModels = _models.value
            val modelName = allModels.find { it.id == loadedModelId }?.name ?: loadedModelId
            _statusText.value = "Đang dùng: $modelName"
        } else {
            val allModels = _models.value
            val downloaded = allModels.filter { it.isDownloaded }
            _statusText.value = when {
                downloaded.isNotEmpty() -> "Đã tải: ${downloaded.joinToString(", ") { it.name }}"
                else -> "Chưa có model nào được tải"
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Float): String = when {
        bytesPerSec <= 0 -> "0 B/s"
        bytesPerSec >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_000_000)
        bytesPerSec >= 1_000 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1_000)
        else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
    }

    override fun onCleared() {
        super.onCleared()
        downloadJobs.values.forEach { it.cancel() }
    }
}