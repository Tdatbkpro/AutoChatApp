package com.example.autochat.llm

import android.util.Log
import javax.inject.Inject

class  LlmEngineFactory @Inject constructor(
private val llamaEngine: LlmEngine,
private val onnxEngine:  OnnxEngine,
private val modelManager: ModelManager,
) {
    companion object {
        private const val TAG = "LlmEngineFactory"
    }

    /**
     * Trả về engine phù hợp cho [modelId].
     * Engine được trả về CHƯA load model — caller phải gọi [LlmEngineInterface.loadModel].
     */
    fun getEngine(modelId: String): LlmEngineInterface {
        val type = modelManager.getModelType(modelId)
        Log.d(TAG, "getEngine: modelId=$modelId type=$type")

        return when {
            type == ModelType.ONNX            -> onnxEngine
            modelId.contains("onnx", ignoreCase = true) -> onnxEngine
            else                              -> llamaEngine
        }.also {
            Log.d(TAG, "→ using ${it::class.simpleName}")
        }
    }

    /**
     * Tiện ích: load model và trả về engine đã sẵn sàng.
     * Throws nếu load thất bại.
     */
    suspend fun loadAndGet(modelId: String): LlmEngineInterface {
        val engine = getEngine(modelId)
        val result = engine.loadModel(modelId)
        if (result.isFailure) {
            throw result.exceptionOrNull()
                ?: Exception("Load model thất bại: $modelId")
        }
        return engine
    }

    /** Engine đang active (đã load model), hoặc null nếu chưa có. */
    fun getActiveEngine(): LlmEngineInterface? = when {
        onnxEngine.isLoaded()  -> onnxEngine
        llamaEngine.isLoaded() -> llamaEngine
        else                   -> null
    }
}
