package com.example.autochat

import com.example.autochat.domain.model.CodeResult
import com.example.autochat.domain.model.Judge0Request
import com.example.autochat.remote.api.Judge0Api
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CodeExecutor @Inject constructor(
    private val judge0Api: Judge0Api
) {
    companion object {
        val SUPPORTED = mapOf(
            "c"          to 50,
            "cpp"        to 54,
            "python"     to 71,
            "java"       to 62,
            "javascript" to 63,
            "js"         to 63,
            "kotlin"     to 78,
            "go"         to 60,
            "rust"       to 73,
            "swift"      to 83,
            "typescript" to 74,
            "bash"       to 46,
            "sql"        to 82,
        )
    }

    suspend fun execute(
        language: String,
        code: String,
        stdin: String = ""
    ): CodeResult {
        val languageId = SUPPORTED[language.lowercase()]
            ?: return CodeResult.Error("Ngôn ngữ '$language' chưa được hỗ trợ")

        return try {
            val request = Judge0Request(
                sourceCode = code,
                languageId = languageId,
                stdin      = stdin
            )

            // ✅ Gửi body dạng base64, kèm wait=true và base64_encoded=true
            val response = judge0Api.execute(
                body          = request.toBase64Map(),
                base64Encoded = true,
                wait          = true
            )

            if (response.isSuccessful) {
                val body = response.body()
                    ?: return CodeResult.Error("Empty response")

                val statusId = body.status.id

                when {
                    // 1–2: Queue/Processing — không nên xảy ra với wait=true
                    statusId in 1..2 ->
                        CodeResult.Error("⏳ Server đang xử lý, vui lòng thử lại")

                    // 3–4: Accepted / Wrong Answer (trong context này đều là output thành công)
                    statusId in 3..4 ->
                        CodeResult.Success(
                            output = body.resolveOutput(),
                            time   = body.time,
                            memory = body.memory
                        )

                    // 5: Time Limit Exceeded
                    statusId == 5 ->
                        CodeResult.Error("⏱ Time limit exceeded")

                    // 6: Compilation Error
                    statusId == 6 ->
                        CodeResult.CompileError(body.resolveError())

                    // 7–12: Runtime Errors (SIGSEGV, SIGABRT, SIGFPE, SIGKILL, SIGXFSZ, NZEC)
                    statusId in 7..12 ->
                        CodeResult.RuntimeError(body.resolveError())

                    // 13: Internal Error
                    statusId == 13 ->
                        CodeResult.Error("❌ Internal server error")

                    // 14: Exec Format Error
                    statusId == 14 ->
                        CodeResult.Error("❌ Exec format error")

                    else ->
                        CodeResult.Error(body.status.description)
                }
            } else {
                val errBody = response.errorBody()?.string()
                when (response.code()) {
                    429  -> CodeResult.Error("⚠️ Rate limit — thử lại sau vài giây")
                    503  -> CodeResult.Error("⚠️ Server tạm thời không khả dụng")
                    else -> CodeResult.Error("HTTP ${response.code()}: $errBody")
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            CodeResult.Error("⏱ Request timeout — server phản hồi quá chậm")
        } catch (e: java.net.UnknownHostException) {
            CodeResult.Error("❌ Không có kết nối mạng")
        } catch (e: Exception) {
            CodeResult.Error(e.message ?: "Network error")
        }
    }
}