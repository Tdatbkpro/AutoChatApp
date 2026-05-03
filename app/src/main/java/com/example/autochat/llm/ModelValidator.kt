// llm/ModelValidator.kt
package com.example.autochat.llm

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelValidator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val estimatedRAM: Long = 0,
        val estimatedStorage: Long = 0
    )

    companion object {
        // GGUF magic number
        private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"
        private const val MIN_FREE_RAM_MB = 512L  // RAM trống tối thiểu 512MB
        private const val MAX_MODEL_SIZE_RATIO = 0.9f  // Model không quá 70% RAM
    }

    fun validateModelFile(filePath: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val file = File(filePath)

        // Check file exists
        if (!file.exists()) {
            return ValidationResult(false, listOf("File không tồn tại: $filePath"))
        }

        // Check file size
        val fileSizeMB = file.length() / (1024 * 1024)
        if (fileSizeMB <= 0) {
            errors.add("File rỗng hoặc kích thước không hợp lệ")
        }

        // Check GGUF format
        if (!isValidGGUFFormat(file)) {
            errors.add("File không đúng định dạng GGUF")
        }

        // Check RAM availability
        val availableRAM = getAvailableRAM()
        if (availableRAM < MIN_FREE_RAM_MB) {
            warnings.add("RAM trống thấp (${availableRAM}MB), model có thể chạy chậm")
        }

        if (fileSizeMB > availableRAM * MAX_MODEL_SIZE_RATIO) {
            warnings.add("Model quá lớn(${fileSizeMB}MB / ${availableRAM}MB), có thể gây crash")
        }

        // Check storage
        val freeStorage = getFreeStorage()
        if (freeStorage < fileSizeMB * 2) {
            warnings.add("Dung lượng trống thấp (${freeStorage}MB)")
        }

        // Check file extension
        if (!file.name.endsWith(".gguf", true)) {
            warnings.add("File không có đuôi .gguf, có thể không tương thích")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            estimatedRAM = fileSizeMB * 2,  // Ước tính RAM gấp đôi file size
            estimatedStorage = fileSizeMB
        )
    }

    fun validateModelUrl(url: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (url.isBlank()) {
            errors.add("URL không được để trống")
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            errors.add("URL không hợp lệ, phải bắt đầu bằng http:// hoặc https://")
        }

        // Check common model hosting sites
        val trustedHosts = listOf(
            "huggingface.co", "hf-mirror.com",
            "github.com", "modelscope.cn"
        )

        val isTrusted = trustedHosts.any { url.contains(it, true) }
        if (!isTrusted) {
            warnings.add("URL không từ nguồn quen thuộc, hãy kiểm tra kỹ")
        }

        // Check filename
        val filename = url.substringAfterLast("/").substringBefore("?")
        if (!filename.endsWith(".gguf", true)) {
            warnings.add("URL có thể không phải file GGUF: $filename")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    fun estimateModelRequirements(filePath: String): Map<String, Long> {
        val file = File(filePath)
        val fileSizeMB = file.length() / (1024 * 1024)

        return mapOf(
            "fileSize" to fileSizeMB,
            "estimatedRAM" to (fileSizeMB * 2),  // RAM ~2x file size
            "minRAM" to (fileSizeMB * 1.5).toLong(),
            "freeStorage" to getFreeStorage(),
            "availableRAM" to getAvailableRAM()
        )
    }

    fun canRunModel(filePath: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false

        val fileSizeMB = file.length() / (1024 * 1024)
        val availableRAM = getAvailableRAM()

        return availableRAM > fileSizeMB * 1.5 &&  // Cần ít nhất 1.5x RAM
                availableRAM > MIN_FREE_RAM_MB &&
                getFreeStorage() > 0
    }

    private fun isValidGGUFFormat(file: File): Boolean {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 4) return false
                val magic = ByteArray(4)
                raf.readFully(magic)
                magic.contentEquals(GGUF_MAGIC)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getAvailableRAM(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024 * 1024)  // MB
    }

    private fun getFreeStorage(): Long {
        val stat = StatFs(Environment.getDataDirectory().path)
        return (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)  // MB
    }

    fun getDeviceInfo(): Map<String, String> {
        return mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkVersion" to Build.VERSION.SDK_INT.toString(),
            "cpuAbi" to Build.SUPPORTED_ABIS.joinToString(", "),
            "ramTotal" to "${getTotalRAM()}",
            "ramAvailable" to "${getAvailableRAM()}",
            "storageFree" to "${getFreeStorage()}"
        )
    }

    private fun getTotalRAM(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem / (1024 * 1024)
    }
}