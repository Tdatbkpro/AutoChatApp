package com.example.autochat.llm

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnnxEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
) : LlmEngineInterface {

    companion object {
        private const val TAG            = "OnnxEngine"
        private const val MAX_NEW_TOKENS = 512
        private const val CONTEXT_LENGTH = 1024
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var ortEnv: OrtEnvironment?   = null
    private var ortSession: OrtSession?   = null
    private var tokenizer: OnnxTokenizer? = null
    private var currentModelId: String?   = null

    // Thông số model — đọc từ session input shapes khi load
    private var numLayers: Int = 6
    private var numHeads:  Int = 8
    private var headDim:   Int = 64

    @Volatile private var isModelLoaded   = false
    private val isStopped = AtomicBoolean(false)

    // ── Load ──────────────────────────────────────────────────────────────────

    override suspend fun loadModel(modelId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                unloadModel()

                val modelDir = modelManager.getModelDir(modelId)
                    ?: return@withContext Result.failure(
                        Exception("Không tìm thấy thư mục model: $modelId")
                    )

                val onnxFile = findOnnxFile(modelDir)
                    ?: return@withContext Result.failure(
                        Exception("Không tìm thấy file .onnx trong $modelDir")
                    )

                Log.d(TAG, "Using ONNX file: ${onnxFile.absolutePath}")

                val env  = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setInterOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }

                val session = env.createSession(onnxFile.absolutePath, opts)

                // ── Log input/output info ─────────────────────────────────────
                Log.d(TAG, "Inputs : ${session.inputNames.toList()}")
                Log.d(TAG, "Outputs: ${session.outputNames.toList()}")

                // ── Detect model shape từ past_key_values input ───────────────
                detectModelShape(session)

                val tok = OnnxTokenizer.fromDir(modelDir)

                ortEnv         = env
                ortSession     = session
                tokenizer      = tok
                currentModelId = modelId
                isModelLoaded  = true
                Log.d(TAG, "EOS ids: ${tok.getEosId()}")
                Log.d(TAG, "im_end id từ vocab: ${tok.getTokenId("<|im_end|>")}")
                Log.d(TAG, "✅ ONNX loaded: $modelId | layers=$numLayers heads=$numHeads headDim=$headDim")
                Result.success(true)

            } catch (e: Exception) {
                Log.e(TAG, "loadModel failed: ${e.message}", e)
                isModelLoaded = false
                Result.failure(e)
            }
        }

    /**
     * Đọc numLayers từ số lượng past_key_values inputs.
     * Đọc numHeads và headDim từ shape của input đó.
     */
    private fun detectModelShape(session: OrtSession) {
        val kvInputs = session.inputNames.filter { it.startsWith("past_key_values.") && it.endsWith(".key") }
        numLayers = kvInputs.size.coerceAtLeast(1)

        // Thử đọc shape từ input info
        try {
            val firstKvInfo = session.inputInfo["past_key_values.0.key"]
            val shape = (firstKvInfo?.info as? ai.onnxruntime.TensorInfo)?.shape
            if (shape != null && shape.size >= 4) {
                // shape: [batch, numHeads, seqLen, headDim]
                numHeads = shape[1].toInt().coerceAtLeast(1)
                headDim  = shape[3].toInt().coerceAtLeast(1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not detect shape from input info, using defaults: ${e.message}")
            // distilgpt2 defaults: 6 layers, 8 heads, 64 head_dim
            numHeads = 8
            headDim  = 64
        }

        Log.d(TAG, "Model shape: layers=$numLayers heads=$numHeads headDim=$headDim")
    }

    // ── Generate ──────────────────────────────────────────────────────────────

    override suspend fun generate(
        prompt: String,
        onToken: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val session = ortSession
            ?: return@withContext Result.failure(Exception("Model chưa load"))
        val tok = tokenizer
            ?: return@withContext Result.failure(Exception("Tokenizer chưa khởi tạo"))
        val env = ortEnv
            ?: return@withContext Result.failure(Exception("ORT env null"))

        isStopped.set(false)

        try {
            // ── Tokenize prompt ───────────────────────────────────────────────
            val inputIds = tok.encode(prompt).toMutableList()
            Log.d(TAG, "Encoded ${inputIds.size} tokens")

            if (inputIds.isEmpty()) {
                return@withContext Result.failure(Exception("Tokenizer trả về empty"))
            }

            // Truncate nếu quá dài
            if (inputIds.size > CONTEXT_LENGTH - MAX_NEW_TOKENS) {
                val keepFrom = inputIds.size - (CONTEXT_LENGTH - MAX_NEW_TOKENS)
                repeat(keepFrom) { inputIds.removeAt(0) }
            }

            val response   = StringBuilder()
            var generated  = 0
            var kvCache: Map<String, OnnxTensor>? = null
            Log.d(TAG, "Prompt (first 200 chars): ${prompt.take(200)}")
            // ── Greedy decode loop ────────────────────────────────────────────
            while (generated < MAX_NEW_TOKENS && !isStopped.get()) {
                val currentIds = if (kvCache == null) inputIds.map { it } else listOf(inputIds.last())
                val inferResult = runInference(env, session, currentIds, kvCache) ?: break

                val (nextTokenId, newKvCache) = inferResult
                kvCache?.values?.forEach { runCatching { it.close() } }
                kvCache = newKvCache

                // ✅ Check EOS theo id — TRƯỚC KHI decode
                if (tok.isEos(nextTokenId)) {
                    Log.d(TAG, "EOS at step $generated, token=$nextTokenId")
                    break
                }

                val word = tok.decode(listOf(nextTokenId))

                // ✅ Check EOS theo string — catch các token dạng "|im_continue|>" bị decode lạ
                if (word.trim() in setOf(
                        "<|im_end|>", "<|im_start|>", "<|im_continue|>",
                        "<|endoftext|>", "</s>", "<eos>", "<|eot_id|>", "<end_of_turn>"
                    ) || word.contains("|im_continue|>") || word.contains("|im_start|>")
                ) {
                    Log.d(TAG, "EOS string at step $generated: '$word'")
                    break
                }

                if (word.isEmpty()) {
                    inputIds.add(nextTokenId)
                    generated++
                    continue
                }

                response.append(word)
                withContext(Dispatchers.Main) { onToken(word) }
                inputIds.add(nextTokenId)
                generated++
            }

            // Cleanup KV cache cuối
            kvCache?.values?.forEach { runCatching { it.close() } }

            val cleaned = cleanResponse(response.toString())
            Log.d(TAG, "Done: $generated tokens, response='${cleaned.take(100)}'")
            Result.success(cleaned.ifEmpty { "..." })

        } catch (e: Exception) {
            Log.e(TAG, "generate error: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Chạy 1 forward pass.
     * @param inputIds token ids cần xử lý (full prompt lần đầu, 1 token các lần sau)
     * @param pastKvCache KV cache từ bước trước (null = lần đầu)
     * @return Pair(nextTokenId, newKvCache) hoặc null nếu lỗi
     */
    private fun runInference(
        env: OrtEnvironment,
        session: OrtSession,
        inputIds: List<Long>,
        pastKvCache: Map<String, OnnxTensor>?,
    ): Pair<Long, Map<String, OnnxTensor>>? {
        val inputs = mutableMapOf<String, OnnxTensor>()
        val inputNames = session.inputNames  // ✅ đọc từ session

        return try {
            val seqLen    = inputIds.size.toLong()
            val batchSize = 1L
            val pastSeqLen = getPastSeqLen(pastKvCache)
            val totalSeqLen = pastSeqLen + seqLen

            // ── input_ids ─────────────────────────────────────────────────────
            inputs["input_ids"] = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(inputIds.toLongArray()),
                longArrayOf(batchSize, seqLen)
            )

            // ── attention_mask ────────────────────────────────────────────────
            if ("attention_mask" in inputNames) {
                inputs["attention_mask"] = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(LongArray(totalSeqLen.toInt()) { 1L }),
                    longArrayOf(batchSize, totalSeqLen)
                )
            }

            // ── position_ids (Qwen2, LLaMA có, distilgpt2 không có) ──────────
            if ("position_ids" in inputNames) {
                val posIds = LongArray(inputIds.size) { i -> pastSeqLen + i }
                inputs["position_ids"] = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(posIds),
                    longArrayOf(batchSize, seqLen)
                )
            }

            // ── use_cache_branch (chỉ merged model có) ────────────────────────
            if ("use_cache_branch" in inputNames) {
                inputs["use_cache_branch"] = OnnxTensor.createTensor(
                    env,
                    booleanArrayOf(pastKvCache != null)
                )
            }

            // ── past_key_values ───────────────────────────────────────────────
            for (i in 0 until numLayers) {
                val keyName = "past_key_values.$i.key"
                val valName = "past_key_values.$i.value"
                if (keyName !in inputNames) continue

                if (pastKvCache != null && pastKvCache.containsKey(keyName)) {
                    inputs[keyName] = pastKvCache[keyName]!!
                    inputs[valName] = pastKvCache[valName]!!
                } else {
                    val emptyShape = longArrayOf(batchSize, numHeads.toLong(), 0L, headDim.toLong())
                    inputs[keyName] = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(0)), emptyShape)
                    inputs[valName] = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(0)), emptyShape)
                }
            }

            // ── Run ───────────────────────────────────────────────────────────
            val outputs = session.run(inputs)

            // ── Logits ────────────────────────────────────────────────────────
            val logitsOnnxVal = outputs[0] as OnnxTensor
            @Suppress("UNCHECKED_CAST")
            val lastLogits = (logitsOnnxVal.value as Array<Array<FloatArray>>)[0].last()
            val nextTokenId = lastLogits.indices.maxByOrNull { lastLogits[it] }?.toLong() ?: 0L

            // ── KV cache mới ──────────────────────────────────────────────────
            val newKvCache = mutableMapOf<String, OnnxTensor>()
            for (i in 0 until numLayers) {
                val keyIdx = 1 + i * 2
                val valIdx = 2 + i * 2
                if (keyIdx < outputs.size() && valIdx < outputs.size()) {
                    newKvCache["past_key_values.$i.key"]   = outputs[keyIdx] as OnnxTensor
                    newKvCache["past_key_values.$i.value"] = outputs[valIdx] as OnnxTensor
                }
            }

            // Cleanup
            inputs.filter { it.key !in (pastKvCache?.keys ?: emptySet()) }
                .forEach { runCatching { it.value.close() } }

            Pair(nextTokenId, newKvCache)

        } catch (e: Exception) {
            Log.e(TAG, "runInference error: ${e.message}", e)
            inputs.forEach { runCatching { it.value.close() } }
            null
        }
    }

    private fun getPastSeqLen(pastKvCache: Map<String, OnnxTensor>?): Long {
        if (pastKvCache == null) return 0L
        return try {
            val shape = (pastKvCache["past_key_values.0.key"]?.info as? ai.onnxruntime.TensorInfo)?.shape
            shape?.getOrNull(2) ?: 0L
        } catch (e: Exception) { 0L }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Tìm file ONNX phù hợp nhất trong thư mục model.
     * Ưu tiên: merged_quantized > quantized > merged > decoder_with_past > model
     */
    private fun findOnnxFile(modelDir: File): File? {
        val priority = listOf(
            "decoder_model_merged_quantized.onnx",
            "decoder_model_quantized.onnx",
            "model_quantized.onnx",
            "decoder_model_merged.onnx",
            "decoder_with_past_model.onnx",
            "decoder_model.onnx",
            "model.onnx",
        )

        val searchDirs = listOf(modelDir, File(modelDir, "onnx"))

        for (filename in priority) {
            for (dir in searchDirs) {
                val f = File(dir, filename)
                if (f.exists()) {
                    Log.d(TAG, "Found ONNX: ${f.absolutePath}")
                    return f
                }
            }
        }

        // Fallback: file .onnx đầu tiên tìm được
        return modelDir.walkTopDown()
            .filter { it.isFile && it.extension == "onnx" }
            .firstOrNull()
    }

    private fun cleanResponse(text: String): String {
        var r = text
        r = r.replace(Regex("<think>[\\s\\S]*?</think>", setOf(RegexOption.DOT_MATCHES_ALL)), "")
        r = r.replace(Regex("<think>[\\s\\S]*",          setOf(RegexOption.DOT_MATCHES_ALL)), "")
        r = r.replace(Regex("</?think>|</?response>|</?reasoning>"), "")
        // ✅ Thêm filter Qwen special tokens
        r = r.replace("<|im_end|>", "")
            .replace("<|im_start|>", "")
            .replace("<|endoftext|>", "")
            .replace("<|eot_id|>", "")
            .replace("<|end_of_text|>", "")
            .replace("</s>", "")
            .replace("<eos>", "")
            .replace("<end_of_turn>", "")
        // ✅ Xóa role tags còn sót
        r = r.replace(Regex("(user|assistant|system)\\s*"), "")
        r = r.replace(Regex("\\n{3,}"), "\n\n").trim()
        return r
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    override fun stopGeneration() {
        isStopped.set(true)
    }

    override fun unloadModel() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.e(TAG, "unloadModel: ${e.message}")
        }
        ortSession     = null
        ortEnv         = null
        tokenizer      = null
        currentModelId = null
        isModelLoaded  = false
    }

    override fun isLoaded(): Boolean          = isModelLoaded && ortSession != null
    override fun getCurrentModelId(): String? = currentModelId
    override fun isNativeAvailable(): Boolean = try {
        Class.forName("ai.onnxruntime.OrtEnvironment"); true
    } catch (_: ClassNotFoundException) { false }
}