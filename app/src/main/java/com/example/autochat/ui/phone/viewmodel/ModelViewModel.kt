package com.example.autochat.ui.phone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autochat.llm.HFTokenManager
import com.example.autochat.llm.LlmEngine
import com.example.autochat.llm.ModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
    private val hfTokenManager: HFTokenManager
) : ViewModel() {

    private val _models = MutableStateFlow<List<ModelManager.ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelManager.ModelInfo>> = _models.asStateFlow()

    private val _statusText = MutableStateFlow("Chưa có model")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    // ✅ GỘP TẤT CẢ DOWNLOAD STATE VÀO 1 MAP DUY NHẤT
    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    private val _pausedIds = MutableStateFlow<Set<String>>(emptySet())
    val pausedIds: StateFlow<Set<String>> = _pausedIds.asStateFlow()

    private val downloadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun loadModels() {
        _models.value = modelManager.getModelsWithStatus()
        updateStatus()
    }

    fun downloadModel(modelId: String) {
        if (_downloadingIds.value.contains(modelId)) return

        val model = _models.value.find { it.id == modelId }
        val totalBytesValue = model?.sizeMB?.times(1024L * 1024L) ?: 0L

        _downloadingIds.value = _downloadingIds.value + modelId
        _pausedIds.value = _pausedIds.value - modelId

        // ✅ Khởi tạo download state
        val newStates = _downloadStates.value.toMutableMap()
        newStates[modelId] = DownloadState(totalBytes = totalBytesValue)
        _downloadStates.value = newStates

        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()

        val job = viewModelScope.launch {
            modelManager.downloadModel(
                modelId = modelId,
                onProgress = { progress, downloadedBytes ->
                    val now = System.currentTimeMillis()
                    val elapsed = (now - lastTime) / 1000f

                    var speed = _downloadStates.value[modelId]?.speed ?: "0 B/s"

                    if (elapsed >= 0.5f) {
                        val bytesDiff = downloadedBytes - lastBytes
                        val speedBps = if (elapsed > 0) bytesDiff / elapsed else 0f
                        speed = formatSpeed(speedBps)
                        lastBytes = downloadedBytes
                        lastTime = now
                    }

                    // ✅ Cập nhật state
                    val currentStates = _downloadStates.value.toMutableMap()
                    currentStates[modelId] = DownloadState(
                        progress = progress,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytesValue,
                        speed = speed
                    )
                    _downloadStates.value = currentStates
                }
            ).onSuccess {
                _downloadingIds.value = _downloadingIds.value - modelId
                val states = _downloadStates.value.toMutableMap()
                states.remove(modelId)
                _downloadStates.value = states
                loadModels()
                updateStatus()
            }.onFailure {
                if (it !is kotlinx.coroutines.CancellationException) {
                    _downloadingIds.value = _downloadingIds.value - modelId
                    val states = _downloadStates.value.toMutableMap()
                    states.remove(modelId)
                    _downloadStates.value = states
                    loadModels()
                }
            }
        }
        downloadJobs[modelId] = job
    }

    fun pauseDownload(modelId: String) {
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        _downloadingIds.value = _downloadingIds.value - modelId
        _pausedIds.value = _pausedIds.value + modelId
    }

    fun resumeDownload(modelId: String) {
        _pausedIds.value = _pausedIds.value - modelId
        modelManager.deletePartialDownload(modelId)
        downloadModel(modelId)
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
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        _downloadingIds.value = _downloadingIds.value - modelId
        _pausedIds.value = _pausedIds.value - modelId

        val states = _downloadStates.value.toMutableMap()
        states.remove(modelId)
        _downloadStates.value = states

        if (llmEngine.getCurrentModelId() == modelId) {
            llmEngine.unloadModel()
            _activeModelId.value = null
        }

        modelManager.deleteModel(modelId)
        loadModels()
        updateStatus()
    }

    private fun updateStatus() {
        val loadedModel = llmEngine.getCurrentModelId()
        val models = modelManager.getModelsWithStatus()
        val downloaded = models.filter { it.isDownloaded }
        _statusText.value = when {
            loadedModel != null -> "Đang dùng: ${models.find { it.id == loadedModel }?.name ?: loadedModel}"
            downloaded.isNotEmpty() -> "Đã tải: ${downloaded.joinToString(", ") { it.name }}"
            else -> "Chưa có model nào được tải"
        }
    }

    private fun formatSpeed(bytesPerSec: Float): String = when {
        bytesPerSec <= 0 -> "0 B/s"
        bytesPerSec >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_000_000)  // ✅ Locale.US để dùng dấu chấm
        bytesPerSec >= 1_000 -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1_000)
        else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
    }

    override fun onCleared() {
        super.onCleared()
        downloadJobs.values.forEach { it.cancel() }
    }

    val hfToken: StateFlow<String?> = hfTokenManager.token
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveHFToken(token: String) {
        viewModelScope.launch { hfTokenManager.saveToken(token) }
    }

    fun clearHFToken() {
        viewModelScope.launch { hfTokenManager.clearToken() }
    }
}