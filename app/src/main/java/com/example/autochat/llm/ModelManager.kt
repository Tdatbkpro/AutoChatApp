package com.example.autochat.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hfTokenManager: HFTokenManager
) {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024  // 256KB buffer
    }

    data class ModelInfo(
        val id: String,
        val name: String,
        val description: String,
        val sizeMB: Long,
        val downloadUrl: String,
        val filename: String,
        val isDownloaded: Boolean = false,
        val isDownloading: Boolean = false,
        val downloadProgress: Float = 0f,
        val downloadedSizeMB: Long = 0
    ) {
        fun getDownloadFullUrl() = "$downloadUrl$filename?download=true"
    }

    val availableModels = listOf(
        ModelInfo(
            id = "llama-3.2-1b",
            name = "Llama 3.2 1B",
            description = "Meta AI - Cân bằng, ổn định",
            sizeMB = 800,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/",
            filename = "Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        ),
        ModelInfo(
            id = "smollm2-1.7b",
            name = "SmolLM2 1.7B",
            description = "HuggingFace - Nhẹ, nhanh",
            sizeMB = 1100,
            downloadUrl = "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/",
            filename = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf"
        ),
        ModelInfo(
            id = "qwen2.5-1.5b",
            name = "Qwen2.5 1.5B",
            description = "Alibaba - Hỗ trợ tiếng Việt tốt",
            sizeMB = 1400,
            downloadUrl = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/",
            filename = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
        ),
        ModelInfo(
            id = "tinyllama-1.1b",
            name = "TinyLlama 1.1B ⚡",
            description = "Siêu nhẹ ~600MB RAM - Khuyên dùng",
            sizeMB = 670,
            downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/",
            filename = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf"
        ),
        ModelInfo(
            id = "qwen2.5-0.5b",
            name = "Qwen2.5 0.5B 🇻🇳",
            description = "Tiếng Việt ổn, ~400MB RAM",
            sizeMB = 395,
            downloadUrl = "https://huggingface.co/Growcompany/Qwen2.5-0.5B-Instruct-Q4_K_M-GGUF/resolve/main/",
            filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        )
    )

    fun getModelsWithStatus(): List<ModelInfo> {
        return availableModels.map { model ->
            val file = File(modelsDir, model.filename)
            val fileSize = file.length()
            // ✅ Dùng kích thước file thực tế nếu đã tải, không dùng sizeMB cố định
            val expectedSize = if (file.exists() && fileSize > 0) {
                fileSize  // Dùng kích thước thực tế của file đã tải
            } else {
                model.sizeMB * 1024L * 1024L  // Dùng size khai báo cho file chưa tải
            }

            val isFullyDownloaded = file.exists() && fileSize > 0 &&
                    fileSize >= model.sizeMB * 1024L * 1024L * 0.95f

            model.copy(
                isDownloaded = isFullyDownloaded,
                downloadedSizeMB = if (file.exists()) fileSize / (1024L * 1024L) else 0
            )
        }
    }

    fun deletePartialDownload(modelId: String) {
        val model = availableModels.find { it.id == modelId } ?: return
        File(modelsDir, model.filename).delete()
    }

    fun isModelDownloaded(modelId: String): Boolean {
        val model = availableModels.find { it.id == modelId } ?: return false
        val file = File(modelsDir, model.filename)
        return file.exists() && file.length() >= model.sizeMB * 1024L * 1024L * 0.95f
    }

    fun getModelPath(modelId: String): String? {
        val model = availableModels.find { it.id == modelId } ?: return null
        val file = File(modelsDir, model.filename)
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Tải model single-thread đơn giản, mirror nhanh hơn
     */
    suspend fun downloadModel(
        modelId: String,
        onProgress: (Float, Long) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val model = availableModels.find { it.id == modelId }
            ?: return@withContext Result.failure(Exception("Model not found"))

        val file = File(modelsDir, model.filename)

        if (file.exists() && file.length() >= model.sizeMB * 1024L * 1024L * 0.95f) {
            return@withContext Result.success(file.absolutePath)
        }

        if (file.exists()) file.delete()

        return@withContext try {
            val token = hfTokenManager.token.firstOrNull()

            // Mirror nhanh hơn ở VN
            val url = model.downloadUrl
                .replace("https://huggingface.co", "https://hf-mirror.com") +
                    "${model.filename}?download=true"

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")

            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful) {
                response.close()
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty body"))
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            // Trong downloadModel, sửa lại:
            file.outputStream().buffered(BUFFER_SIZE).use { output ->
                body.byteStream().buffered(BUFFER_SIZE).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var lastUpdate = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        // ✅ Gọi callback trực tiếp, không withContext
                        // (vì ViewModel đã nhận callback trong Main thread)
                        if (totalBytes > 0 && (downloadedBytes - lastUpdate) >= 512 * 1024) {
                            onProgress(downloadedBytes.toFloat() / totalBytes, downloadedBytes)
                            lastUpdate = downloadedBytes
                        }
                    }
                }
            }

            response.close()

            // Báo 100%
            withContext(Dispatchers.Main) {
                onProgress(1f, file.length())
            }

            Result.success(file.absolutePath)
        } catch (e: CancellationException) {
            if (file.exists()) file.delete()
            throw e
        } catch (e: Exception) {
            if (file.exists()) file.delete()
            Result.failure(e)
        }
    }

    fun deleteModel(modelId: String): Boolean {
        val model = availableModels.find { it.id == modelId } ?: return false
        val file = File(modelsDir, model.filename)
        return file.delete()
    }

    fun getStorageInfo(): StorageInfo {
        var totalSize = 0L
        var modelCount = 0

        availableModels.forEach { model ->
            val file = File(modelsDir, model.filename)
            if (file.exists()) {
                modelCount++
                totalSize += file.length()
            }
        }

        return StorageInfo(
            modelCount = modelCount,
            usedMB = totalSize / (1024 * 1024),
            freeMB = modelsDir.freeSpace / (1024 * 1024)
        )
    }

    data class StorageInfo(
        val modelCount: Int,
        val usedMB: Long,
        val freeMB: Long
    )
}