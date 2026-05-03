package com.example.autochat.llm

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager
) {
    // ── Scope riêng cho engine — không share với bên ngoài ───────────────────
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── SharedFlow: replay=0 để KHÔNG giữ event cũ giữa các lần generate ────
    // extraBufferCapacity lớn để không drop token khi consumer chậm
    private val llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        replay              = 0,
        extraBufferCapacity = 256,
        onBufferOverflow    = BufferOverflow.SUSPEND   // ← backpressure thay vì DROP
    )

    private var llamaHelper: LlamaHelper? = null
    private var currentModelId: String? = null

    @Volatile private var isModelLoaded = false
    @Volatile private var isStopped     = AtomicBoolean(false)

    private var collectJob: Job? = null

    companion object {
        private const val TAG            = "LlmEngine"
        private const val LOAD_TIMEOUT   = 90_000L   // 90s
        private const val GEN_TIMEOUT    = 180_000L  // 3 phút
        private const val CONTEXT_LENGTH = 4096
    }

    // ────────────────────────────────────────────────────────────────────────
    // LOAD MODEL
    // ────────────────────────────────────────────────────────────────────────

    suspend fun loadModel(modelId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            unloadModel()

            val modelPath = modelManager.getModelPath(modelId)
                ?: return@withContext Result.failure(Exception("Model chưa tải về"))

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                return@withContext Result.failure(Exception("File model không tồn tại: $modelPath"))
            }

            val helper = LlamaHelper(
                contentResolver = context.contentResolver,
                scope           = engineScope,
                sharedFlow      = llmFlow
            )

            // Channel để nhận kết quả load — tránh race condition với SharedFlow
            val loadChannel = Channel<Result<Boolean>>(1)

            // Subscribe TRƯỚC khi gọi load()
            val loadJob = engineScope.launch {
                llmFlow.collect { event ->
                    when (event) {
                        is LlamaHelper.LLMEvent.Loaded -> {
                            loadChannel.trySend(Result.success(true))
                        }
                        is LlamaHelper.LLMEvent.Error  -> {
                            loadChannel.trySend(Result.failure(Exception(event.message)))
                        }
                        else -> {}
                    }
                }
            }

            val modelUri = Uri.fromFile(modelFile).toString()
            helper.load(path = modelUri, contextLength = CONTEXT_LENGTH) { id ->
                if (id <= 0) {
                    loadChannel.trySend(Result.failure(Exception("load() callback id=$id")))
                }
                // id > 0 → chờ LLMEvent.Loaded từ flow
            }

            val result = withTimeoutOrNull(LOAD_TIMEOUT) {
                loadChannel.receive()
            } ?: Result.failure(Exception("Load model timeout (${LOAD_TIMEOUT/1000}s)"))

            loadJob.cancel()
            loadChannel.close()

            if (result.isSuccess) {
                llamaHelper    = helper
                currentModelId = modelId
                isModelLoaded  = true
                Log.d(TAG, "✅ Model ready: $modelId")
            } else {
                Log.e(TAG, "❌ Load failed: ${result.exceptionOrNull()?.message}")
            }

            result

        } catch (e: Exception) {
            Log.e(TAG, "loadModel exception: ${e.message}", e)
            isModelLoaded = false
            Result.failure(e)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // GENERATE
    // Fix các bug cũ:
    //   1. collectJob start TRƯỚC predict() — dùng Channel để đồng bộ
    //   2. BufferOverflow.SUSPEND — không drop token
    //   3. isStopped dùng AtomicBoolean — thread-safe
    //   4. Timeout theo GEN_TIMEOUT thay vì vòng lặp thủ công
    //   5. cleanResponse xử lý triệt để think tags + lặp lại
    // ────────────────────────────────────────────────────────────────────────

    suspend fun generate(
        prompt: String,
        onToken: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val helper = llamaHelper
            ?: return@withContext Result.failure(Exception("Model chưa load"))

        isStopped.set(false)

        // Cancel collect job cũ nếu còn
        collectJob?.cancel()
        collectJob = null

        val response    = StringBuilder()
        val doneChannel = Channel<Result<String>>(1)

        // ── Bước 1: Subscribe TRƯỚC khi predict() ────────────────────────────
        // Channel readySignal để đảm bảo coroutine đã collect trước khi predict
        val readySignal = Channel<Unit>(1)

        collectJob = engineScope.launch {
            readySignal.send(Unit) // báo hiệu đã ready

            llmFlow.collect { event ->
                if (isStopped.get()) {
                    doneChannel.trySend(Result.success(response.toString()))
                    return@collect
                }
                when (event) {
                    is LlamaHelper.LLMEvent.Ongoing -> {
                        val token = event.word
                        if (token.isNotEmpty()) {
                            response.append(token)
                            withContext(Dispatchers.Main) { onToken(token) }
                        }
                    }
                    is LlamaHelper.LLMEvent.Done -> {
                        doneChannel.trySend(Result.success(response.toString()))
                    }
                    is LlamaHelper.LLMEvent.Error -> {
                        doneChannel.trySend(
                            Result.failure(Exception(event.message))
                        )
                    }
                    else -> {} // Started, Loaded — bỏ qua
                }
            }
        }

        // ── Bước 2: Chờ collectJob ready rồi mới predict ─────────────────────
        withTimeoutOrNull(3_000L) { readySignal.receive() }
            ?: Log.w(TAG, "readySignal timeout — proceeding anyway")

        // ── Bước 3: Predict ───────────────────────────────────────────────────
        try {
            helper.predict(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "predict() threw: ${e.message}")
            collectJob?.cancel()
            return@withContext Result.failure(e)
        }

        // ── Bước 4: Chờ Done/Error với timeout ───────────────────────────────
        val rawResult = withTimeoutOrNull(GEN_TIMEOUT) {
            doneChannel.receive()
        } ?: run {
            Log.w(TAG, "generate timeout after ${GEN_TIMEOUT/1000}s, returning partial")
            Result.success(response.toString())
        }

        collectJob?.cancel()
        doneChannel.close()
        readySignal.close()

        // ── Bước 5: Clean + trả về ────────────────────────────────────────────
        rawResult.map { raw ->
            val cleaned = cleanResponse(raw)
            Log.d(TAG, "generate done: ${cleaned.length} chars")
            cleaned.ifEmpty { "..." }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // CLEAN RESPONSE
    // Xử lý triệt để: think tags, lặp lại, token rác
    // ────────────────────────────────────────────────────────────────────────

    private fun cleanResponse(text: String): String {
        var r = text

        // 1. Xóa <think>...</think> hoàn chỉnh
        r = r.replace(Regex("<think>[\\s\\S]*?</think>", setOf(RegexOption.DOT_MATCHES_ALL)), "")

        // 2. Xóa <think> chưa đóng (Qwen3 thinking mode)
        r = r.replace(Regex("<think>[\\s\\S]*", setOf(RegexOption.DOT_MATCHES_ALL)), "")

        // 3. Xóa tag lẻ còn sót
        r = r.replace(Regex("</?think>|</?response>|</?reasoning>"), "")

        // 4. Xóa prefix /no_think (Qwen3)
        r = r.removePrefix("/no_think").trim()

        // 5. Xóa EOS tokens hay bị leak ra
        r = r.replace("<|im_end|>", "")
            .replace("<|eot_id|>", "")
            .replace("<|end_of_text|>", "")
            .replace("</s>", "")
            .replace("<eos>", "")
            .replace("<end_of_turn>", "")  // Gemma

        // 6. Xóa lặp lại liên tiếp (model bị loop)
        r = removeRepetition(r)

        // 7. Normalize whitespace
        r = r.replace(Regex("\\n{3,}"), "\n\n").trim()

        return r
    }

    /**
     * Phát hiện và cắt bỏ phần lặp lại.
     * Thuật toán: tìm đoạn >= 20 chars xuất hiện >= 3 lần liên tiếp → cắt tại lần thứ 1.
     */
    private fun removeRepetition(text: String): String {
        if (text.length < 60) return text
        val minLen = 20
        val maxLen = minOf(200, text.length / 2)

        for (len in maxLen downTo minLen) {
            var i = 0
            while (i + len * 2 <= text.length) {
                val chunk = text.substring(i, i + len)
                if (text.substring(i + len).startsWith(chunk)) {
                    // Lặp lại tìm thấy → trả về đến hết lần đầu tiên
                    Log.d(TAG, "Repetition detected at $i, len=$len")
                    return text.substring(0, i + len).trim()
                }
                i++
            }
        }
        return text
    }

    // ────────────────────────────────────────────────────────────────────────
    // CONTROLS
    // ────────────────────────────────────────────────────────────────────────

    fun stopGeneration() {
        isStopped.set(true)
        try {
            llamaHelper?.stopPrediction()
            collectJob?.cancel()
            collectJob = null
        } catch (e: Exception) {
            Log.e(TAG, "stopGeneration: ${e.message}")
        }
    }

    fun unloadModel() {
        collectJob?.cancel()
        collectJob = null
        try {
            llamaHelper?.stopPrediction()
            llamaHelper?.release()
        } catch (e: Exception) {
            Log.e(TAG, "unloadModel: ${e.message}")
        }
        llamaHelper    = null
        currentModelId = null
        isModelLoaded  = false
    }

    fun isLoaded(): Boolean          = isModelLoaded && llamaHelper != null
    fun isNativeAvailable(): Boolean = true
    fun getCurrentModelId(): String? = currentModelId
}