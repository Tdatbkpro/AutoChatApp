package com.example.autochat.llm

import android.content.Context
import android.util.Log
import com.example.autochat.data.local.dao.CustomModelDao
import com.example.autochat.data.local.entity.CustomModelEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import okhttp3.*
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val customModelDao: CustomModelDao,
    private val modelValidator: ModelValidator

) {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    companion object {
        private const val BUFFER_SIZE = 4 * 1024 * 1024  // 4MB
    }

    // cancelFlags: set true → dừng vòng I/O ngay sau chunk hiện tại
    private val cancelFlags = mutableMapOf<String, AtomicBoolean>()

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
    )

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
            sizeMB = 900,
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
            val isFullyDownloaded = file.exists() && fileSize > 0 &&
                    fileSize >= model.sizeMB * 1024L * 1024L * 0.95f
            model.copy(
                isDownloaded = isFullyDownloaded,
                downloadedSizeMB = if (file.exists()) fileSize / (1024L * 1024L) else 0
            )
        }
    }

    /** Dừng vòng I/O ngay, KHÔNG xóa file → dùng khi pause */
    fun signalPause(modelId: String) {
        cancelFlags[modelId]?.set(true)
    }

    /** Dừng vòng I/O ngay VÀ xóa file → dùng khi cancel hoàn toàn */
    fun signalCancel(modelId: String) {
        cancelFlags[modelId]?.set(true)
    }

    /** Bytes đã tải của file đang download dở (để resume) */
    fun getPartialSize(modelId: String): Long {
        val model = findModelById(modelId) ?: return 0L
        val file = File(modelsDir, model.filename)
        return if (file.exists()) file.length() else 0L
    }

    fun deletePartialDownload(modelId: String) {
        val model = availableModels.find { it.id == modelId } ?: return
        File(modelsDir, model.filename).delete()
    }

    fun isModelDownloaded(modelId: String): Boolean {
        val model = findModelById(modelId) ?: return false
        val file = File(modelsDir, model.filename)
        return file.exists() && file.length() >= model.sizeMB * 1024L * 1024L * 0.95f
    }

    fun getModelPath(modelId: String): String? {
        val model = findModelById(modelId) ?: return null
        val file = File(modelsDir, model.filename)
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Tải model, hỗ trợ resume qua HTTP Range Request.
     * Nếu file đã tồn tại một phần → tự động tiếp tục từ chỗ đó.
     * @param resume true = tiếp tục từ file dở, false = xóa và tải lại từ đầu
     */
    private fun findModelById(modelId: String): ModelInfo? {
        availableModels.find { it.id == modelId }?.let { return it }
        val entity = runBlocking { customModelDao.getModelById(modelId.removePrefix("custom_")) }
        return entity?.let {
            ModelInfo(
                id = "custom_${it.id}",
                name = it.name,
                description = it.description,
                sizeMB = it.sizeMB,
                downloadUrl = it.downloadUrl,
                filename = it.filename,
                isDownloaded = it.isDownloaded,
                downloadedSizeMB = it.downloadedSizeMB
            )
        }
    }
    suspend fun downloadModel(
        modelId: String,
        resume: Boolean = false,
        onTotalBytes: (Long) -> Unit = {},
        onProgress: (Float, Long) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val model = availableModels.find { it.id == modelId }
            ?: customModelDao.getModelById(modelId.removePrefix("custom_"))?.let { entity ->
                ModelInfo(
                    id = "custom_${entity.id}",
                    name = entity.name,
                    description = entity.description,
                    sizeMB = entity.sizeMB,
                    downloadUrl = entity.downloadUrl,
                    filename = entity.filename,
                    isDownloaded = entity.isDownloaded,
                    downloadedSizeMB = entity.downloadedSizeMB
                )
            }
            ?: return@withContext Result.failure(Exception("Model not found"))

        val file = File(modelsDir, model.filename)

        if (!resume && file.exists()) file.delete()

        val startByte = if (resume && file.exists()) file.length() else 0L

        val cancelled = AtomicBoolean(false)
        cancelFlags[modelId] = cancelled

        return@withContext try {

            val url = if (modelId.startsWith("custom_")) {
                model.downloadUrl  // Custom: url trực tiếp
            } else {
                "https://hf-mirror.com/${
                    model.downloadUrl.removePrefix("https://huggingface.co/")
                }${model.filename}?download=true"
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")

            // Thêm Range header nếu resume
            if (startByte > 0) {
                requestBuilder.header("Range", "bytes=$startByte-")
            }

            val response = client.newCall(requestBuilder.build()).execute()

            // 200 = tải mới, 206 = server hỗ trợ resume và đang gửi tiếp
            if (response.code != 200 && response.code != 206) {
                response.close()
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            // Nếu server trả 200 thay vì 206 khi resume → server không hỗ trợ Range
            // → xóa file cũ, tải lại từ đầu
            if (resume && startByte > 0 && response.code == 200) {
                file.delete()
            }

            val body = response.body
                ?: return@withContext Result.failure(Exception("Empty body"))

            // Content-Length của response 206 là số bytes còn lại, cộng thêm startByte = total
            val contentLength = body.contentLength()
            val totalBytes = if (response.code == 206 && contentLength > 0) {
                contentLength + startByte
            } else {
                contentLength
            }
            if (totalBytes > 0) onTotalBytes(totalBytes)

            // downloadedBytes bắt đầu từ startByte (đã tải trước đó)
            var downloadedBytes = startByte
            val snapBytes = AtomicLong(startByte)

            // Progress thread: update UI mỗi 300ms, không block IO thread
            val progressThread = Thread {
                while (!cancelled.get() && !Thread.currentThread().isInterrupted) {
                    val bytes = snapBytes.get()
                    val progress = if (totalBytes > 0) bytes.toFloat() / totalBytes else -1f
                    onProgress(progress, bytes)
                    try { Thread.sleep(300) } catch (_: InterruptedException) { break }
                }
            }.also { it.isDaemon = true; it.start() }

            try {
                // APPEND nếu resume, ghi mới nếu không
                FileOutputStream(file, resume && startByte > 0).use { rawOut ->
                    val out = BufferedOutputStream(rawOut, BUFFER_SIZE)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead = 0
                        while (!cancelled.get() &&
                            input.read(buffer).also { bytesRead = it } != -1
                        ) {
                            out.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            snapBytes.set(downloadedBytes)
                        }
                        out.flush()
                    }
                }
            } finally {
                progressThread.interrupt()
                progressThread.join(500)
            }

            // Progress lần cuối
            val finalProgress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 1f
            onProgress(finalProgress, downloadedBytes)

            response.close()

            if (cancelled.get()) {
                // Bị pause/cancel → KHÔNG xóa file, ViewModel tự quyết định có xóa không
                throw CancellationException("Stopped")
            }
            if (modelId.startsWith("custom_")) {
                val cleanId = modelId.removePrefix("custom_")
                customModelDao.getModelById(cleanId)?.let { entity ->
                    customModelDao.updateModel(entity.copy(
                        isDownloaded = true,
                        filePath = file.absolutePath,
                        sizeMB = file.length() / (1024 * 1024)
                    ))
                }
            }
            Result.success(file.absolutePath)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            cancelFlags.remove(modelId)
        }
    }

    fun deleteModel(modelId: String): Boolean {
        val model = availableModels.find { it.id == modelId } ?: return false
        return File(modelsDir, model.filename).delete()
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

    data class StorageInfo(val modelCount: Int, val usedMB: Long, val freeMB: Long)

    suspend fun getAllModels(): List<ModelInfo> {
        val builtIn = getModelsWithStatus()
        val custom = customModelDao.getAllModels().firstOrNull()?.map { entity ->
            ModelInfo(
                id = "custom_${entity.id}",
                name = entity.name,
                description = entity.description,
                sizeMB = entity.sizeMB,
                downloadUrl = entity.downloadUrl,
                filename = entity.filename,
                isDownloaded = entity.isDownloaded,
                downloadedSizeMB = entity.downloadedSizeMB
            )
        } ?: emptyList()
        return builtIn + custom
    }

    // ✅ Lấy custom models từ Room
    fun getCustomModels(): List<ModelInfo> {
        return runBlocking {
            customModelDao.getAllModels().firstOrNull()?.map { entity ->
                ModelInfo(
                    id = "custom_${entity.id}",
                    name = entity.name,
                    description = entity.description,
                    sizeMB = entity.sizeMB,
                    downloadUrl = entity.downloadUrl,
                    filename = entity.filename,
                    isDownloaded = entity.isDownloaded,
                    downloadedSizeMB = entity.downloadedSizeMB
                )
            } ?: emptyList()
        }
    }

    // ✅ Thêm custom model
    suspend fun addCustomModel(
        name: String,
        description: String,
        downloadUrl: String,
        filename: String
    ): Result<String> {
        // 1. Validate URL
        val urlValidation = modelValidator.validateModelUrl(downloadUrl)
        if (!urlValidation.isValid) {
            return Result.failure(Exception(urlValidation.errors.joinToString(", ")))
        }

        // 2. ✅ Nếu URL là link repo (không có file .gguf cụ thể) -> tự tìm file
        var finalUrl = downloadUrl
        var finalFilename = filename

        if (!downloadUrl.endsWith(".gguf", true) && !downloadUrl.contains("/resolve/")) {
            // Là link repo -> tìm file GGUF phù hợp
            val foundFile = findBestGGUFFile(downloadUrl)
            if (foundFile == null) {
                return Result.failure(Exception("Không tìm thấy file GGUF trong repo này"))
            }
            finalUrl = foundFile.first
            finalFilename = foundFile.second
        }

        // 3. Check size
        val sizeMB = getUrlFileSize(finalUrl)
        if (sizeMB == null || sizeMB <= 0) {
            return Result.failure(Exception("Không thể truy cập URL hoặc file không hợp lệ"))
        }

        // 4. Check storage
        val freeStorage = modelValidator.getDeviceInfo()["storageFree"]?.toLongOrNull() ?: 0L
        if (sizeMB * 2 > freeStorage) {
            return Result.failure(Exception("Không đủ dung lượng! Cần ${sizeMB * 2}MB"))
        }

        // 5. Check trùng
        val existingModels = getCustomModels()
        if (existingModels.any { it.downloadUrl == finalUrl || it.filename == finalFilename }) {
            return Result.failure(Exception("Model đã tồn tại"))
        }

        val id = "custom_${System.currentTimeMillis()}"
        customModelDao.insertModel(CustomModelEntity(
            id = id,
            name = name,
            description = description,
            sizeMB = sizeMB,
            downloadUrl = finalUrl,
            filename = finalFilename,
            isDownloaded = false
        ))

        return Result.success(id)
    }

    private suspend fun findBestGGUFFile(repoUrl: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            // Dùng API HuggingFace
            val cleanUrl = repoUrl
                .replace("https://hf-mirror.com/", "")
                .replace("https://huggingface.co/", "")
                .trimEnd('/')

            val apiUrl = "https://huggingface.co/api/models/$cleanUrl"

            Log.d("ModelManager", "Fetching: $apiUrl")

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            Log.d("ModelManager", "Response: ${body.take(200)}")

            if (response.code != 200) {
                Log.e("ModelManager", "HTTP ${response.code}")
                return@withContext null
            }

            val json = org.json.JSONObject(body)

            // Try "siblings" first
            var files = json.optJSONArray("siblings")

            // Nếu không có siblings, thử "config" -> "model_types"
            if (files == null || files.length() == 0) {
                // Thử scrape HTML page
                val pageUrl = repoUrl.trimEnd('/') + "/tree/main"
                val pageRequest = Request.Builder().url(pageUrl).get().build()
                val pageResponse = client.newCall(pageRequest).execute()
                val pageBody = pageResponse.body?.string() ?: ""
                pageResponse.close()

                // Regex tìm file .gguf
                val pattern = Regex("""href="[^"]*/([^"/]+\.gguf)"""")
                val matches = pattern.findAll(pageBody).toList()

                if (matches.isEmpty()) return@withContext null

                val bestFile = matches.first().groupValues[1]
                val downloadUrl = "$repoUrl/resolve/main/$bestFile"
                Log.d("ModelManager", "Found from page: $bestFile")
                return@withContext Pair(downloadUrl, bestFile)
            }

            // Parse siblings
            val ggufFiles = mutableListOf<Pair<String, Long>>()
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val fileName = file.optString("rfilename", file.optString("path", ""))
                if (fileName.endsWith(".gguf")) {
                    val size = file.optLong("size", 0)
                    ggufFiles.add(fileName to size)
                    Log.d("ModelManager", "Found GGUF: $fileName (${size}MB)")
                }
            }

            if (ggufFiles.isEmpty()) return@withContext null

            // Ưu tiên Q4_K_M > Q4_K_S > Q5_K_M > Q2_K > file đầu
            val preferred = listOf("Q4_K_M", "Q4_K_S", "Q5_K_M", "Q8_0", "Q2_K", "IQ4_XS")
            for (pref in preferred) {
                val found = ggufFiles.find { it.first.contains(pref, true) }
                if (found != null) {
                    val downloadUrl = "$repoUrl/resolve/main/${found.first}"
                    return@withContext Pair(downloadUrl, found.first)
                }
            }

            // Fallback
            val first = ggufFiles.first()
            val downloadUrl = "$repoUrl/resolve/main/${first.first}"
            Pair(downloadUrl, first.first)
        } catch (e: Exception) {
            Log.e("ModelManager", "findBestGGUFFile error: ${e.message}", e)
            null
        }
    }
    data class GGUFInfo(
        val filename: String,
        val sizeMB: Long,
        val quantization: String
    )

    // Trong getGGUFList - bỏ size, chỉ lấy tên
    suspend fun getGGUFList(repoUrl: String): List<GGUFInfo> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = repoUrl
                .replace("https://hf-mirror.com/", "")
                .replace("https://huggingface.co/", "")
                .trimEnd('/')

            val apiUrl = "https://huggingface.co/api/models/$cleanUrl"

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val response = client.newCall(Request.Builder().url(apiUrl).header("User-Agent", "Mozilla/5.0").get().build()).execute()
            val body = response.body?.string() ?: ""
            response.close()

            val json = org.json.JSONObject(body)
            val siblings = json.optJSONArray("siblings") ?: return@withContext emptyList()

            val result = mutableListOf<GGUFInfo>()
            for (i in 0 until siblings.length()) {
                val file = siblings.getJSONObject(i)
                val fileName = file.optString("rfilename", "")
                if (fileName.endsWith(".gguf") && !fileName.endsWith("-mmproj.gguf")) {
                    val quant = extractQuantization(fileName)
                    // ✅ Lấy size từ HEAD request
                    val size = try {
                        getUrlFileSize("${repoUrl.trimEnd('/')}/resolve/main/$fileName") ?: 0L
                    } catch (e: Exception) { 0L }
                    result.add(GGUFInfo(fileName, size, quant))
                }
            }

            val order = listOf("Q4_K_M", "Q4_K_S", "Q5_K_M", "Q5_K_S", "Q8_0", "Q3_K_M", "Q3_K_S", "Q2_K", "Q6_K", "IQ4")
            result.sortBy { info ->
                val idx = order.indexOfFirst { info.filename.contains(it, true) }
                if (idx == -1) order.size else idx
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Hàm lấy size cho 1 file
     suspend fun getFileSize(repoUrl: String, filename: String): Long = withContext(Dispatchers.IO) {
        try {
            val url = "${repoUrl.trimEnd('/')}/resolve/main/$filename"
            getUrlFileSize(url) ?: 0L
        } catch (e: Exception) { 0L }
    }

    private fun extractQuantization(filename: String): String {
        val quants = listOf("Q2_K", "Q3_K_L", "Q3_K_M", "Q3_K_S", "Q4_K_M", "Q4_K_S", "Q5_K_M", "Q5_K_S", "Q6_K", "Q8_0", "IQ4_XS", "F16", "F32")
        return quants.find { filename.contains(it, true) } ?: "Unknown"
    }
    // ✅ Verify custom model sau khi download
    suspend fun verifyCustomModel(modelId: String): ModelValidator.ValidationResult {
        val entity = customModelDao.getModelById(modelId.removePrefix("custom_"))
            ?: return ModelValidator.ValidationResult(false, listOf("Model không tồn tại"))

        val file = File(modelsDir, entity.filename)
        if (!file.exists()) {
            return ModelValidator.ValidationResult(false, listOf("File chưa được tải"))
        }

        val validation = modelValidator.validateModelFile(file.absolutePath)

        if (validation.isValid) {
            customModelDao.updateModel(entity.copy(
                isVerified = true,
                filePath = file.absolutePath,
                sizeMB = file.length() / (1024 * 1024),
                isDownloaded = true
            ))
        }

        return validation
    }

    // ✅ Xóa custom model
    suspend fun deleteCustomModel(modelId: String) {
        val cleanId = modelId.removePrefix("custom_")
        val entity = customModelDao.getModelById(cleanId) ?: return

        // Xóa file
        File(modelsDir, entity.filename).delete()

        // Xóa khỏi Room
        customModelDao.deleteById(cleanId)
    }
    suspend fun updateCustomModel(
        modelId: String,
        name: String,
        description: String,
        downloadUrl: String,
        filename: String
    ) {
        val cleanId = modelId.removePrefix("custom_")
        customModelDao.getModelById(cleanId)?.let { entity ->
            customModelDao.updateModel(entity.copy(
                name = name,
                description = description,
                downloadUrl = downloadUrl,
                filename = filename
            ))
        }
    }
    private suspend fun getUrlFileSize(url: String): Long? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            // ✅ Dùng GET với Range 0-0 để lấy Content-Length
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()

            val response = client.newCall(request).execute()

            val size = when (response.code) {
                200 -> {
                    // Lấy từ Content-Length
                    response.body?.contentLength()
                }
                206 -> {
                    // Lấy từ Content-Range header
                    val contentRange = response.header("Content-Range") ?: ""
                    contentRange.substringAfterLast("/").toLongOrNull()
                }
                else -> null
            }

            response.close()

            if (size != null && size > 0) size / (1024 * 1024) else null
        } catch (e: Exception) {
            android.util.Log.e("ModelManager", "getUrlFileSize error: ${e.message}")
            null
        }
    }
}