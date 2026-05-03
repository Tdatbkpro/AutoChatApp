package com.example.autochat.domain.model

import android.util.Base64
import com.google.gson.annotations.SerializedName

// ✅ encode source_code và stdin sang base64
data class Judge0Request(
    @SerializedName("source_code")     val sourceCode: String,
    @SerializedName("language_id")     val languageId: Int,
    @SerializedName("stdin")           val stdin: String = "",
    @SerializedName("compile_timeout") val compileTimeout: Int = 10,
    @SerializedName("wall_time_limit") val wallTimeLimit: Double = 10.0
) {
    /** Trả về map đã encode base64 để gửi lên API */
    fun toBase64Map(): Map<String, Any> = mapOf(
        "source_code"     to sourceCode.encodeBase64(),
        "language_id"     to languageId,
        "stdin"           to stdin.encodeBase64(),
        "compile_timeout" to compileTimeout,
        "wall_time_limit" to wallTimeLimit
    )
}

data class Judge0Result(
    @SerializedName("stdout")         val stdoutRaw: String?,
    @SerializedName("stderr")         val stderrRaw: String?,
    @SerializedName("compile_output") val compileOutputRaw: String?,
    @SerializedName("message")        val message: String?,
    @SerializedName("status")         val status: Judge0Status,
    @SerializedName("time")           val time: String?,
    @SerializedName("memory")         val memory: Int?
) {
    // ✅ Tự decode base64 khi đọc
    val stdout: String?        get() = stdoutRaw?.decodeBase64Safe()
    val stderr: String?        get() = stderrRaw?.decodeBase64Safe()
    val compileOutput: String? get() = compileOutputRaw?.decodeBase64Safe()

    /**
     * Output ưu tiên: stdout → compile_output → stderr → message
     */
    fun resolveOutput(): String =
        stdout?.trim()?.takeIf { it.isNotEmpty() }
            ?: compileOutput?.trim()?.takeIf { it.isNotEmpty() }
            ?: stderr?.trim()?.takeIf { it.isNotEmpty() }
            ?: message?.trim()?.takeIf { it.isNotEmpty() }
            ?: "(no output)"

    /**
     * Error ưu tiên: compile_output → stderr → message → description
     */
    fun resolveError(): String =
        compileOutput?.trim()?.takeIf { it.isNotEmpty() }
            ?: stderr?.trim()?.takeIf { it.isNotEmpty() }
            ?: message?.trim()?.takeIf { it.isNotEmpty() }
            ?: status.description
}

data class Judge0Status(
    val id: Int,
    val description: String
)

sealed class CodeResult {
    data class Success(
        val output: String,
        val time: String? = null,
        val memory: Int? = null
    ) : CodeResult()
    data class CompileError(val error: String) : CodeResult()
    data class RuntimeError(val error: String) : CodeResult()
    data class Error(val message: String) : CodeResult()
}

// ── Base64 helpers ───────────────────────────────────────────────────────────

fun String.encodeBase64(): String =
    Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

fun String.decodeBase64Safe(): String = try {
    String(Base64.decode(this, Base64.NO_WRAP), Charsets.UTF_8)
} catch (_: Exception) {
    this // nếu server không trả base64 thì giữ nguyên
}