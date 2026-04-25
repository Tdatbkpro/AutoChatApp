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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var llamaHelper: LlamaHelper? = null
    private var currentModelId: String? = null
    private var isModelLoaded = false
    private var collectJob: Job? = null

    companion object {
        private const val TAG = "LlmEngine"
    }

    suspend fun loadModel(modelId: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                unloadModel()

                val modelPath = modelManager.getModelPath(modelId)
                    ?: return@withContext Result.failure(Exception("Model chưa tải về"))

                val modelFile = File(modelPath)
                if (!modelFile.exists()) {
                    return@withContext Result.failure(Exception("File model không tồn tại"))
                }

                val helper = LlamaHelper(
                    contentResolver = context.contentResolver,
                    scope = scope,
                    sharedFlow = llmFlow
                )

                val modelUri = Uri.fromFile(modelFile).toString()
                val latch = CountDownLatch(1)
                var loadError: Exception? = null

                helper.load(
                    path = modelUri,
                    contextLength = 2048
                ) { id ->
                    if (id > 0) {
                        Log.d(TAG, "Model loaded id=$id")
                        isModelLoaded = true
                    } else {
                        loadError = Exception("Load model thất bại")
                    }
                    latch.countDown()
                }

                // Lắng nghe event Loaded/Error trong khi chờ
                val loadListenJob = scope.launch {
                    llmFlow.collect { event ->
                        when (event) {
                            is LlamaHelper.LLMEvent.Loaded -> latch.countDown()
                            is LlamaHelper.LLMEvent.Error -> {
                                loadError = Exception(event.message)
                                latch.countDown()
                            }
                            else -> {}
                        }
                    }
                }

                latch.await(60, TimeUnit.SECONDS)
                loadListenJob.cancel()

                if (loadError != null) {
                    return@withContext Result.failure(loadError!!)
                }

                llamaHelper = helper
                currentModelId = modelId
                Log.d(TAG, "Model ready: $modelId")
                Result.success(true)

            } catch (e: Exception) {
                Log.e(TAG, "loadModel error: ${e.message}")
                isModelLoaded = false
                Result.failure(e)
            }
        }
    }

    suspend fun generate(
        prompt: String,
        onToken: (String) -> Unit
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val helper = llamaHelper
                    ?: return@withContext Result.failure(Exception("Model chưa load"))

                val response = StringBuilder()
                val latch = CountDownLatch(1)
                var generateError: Exception? = null

                collectJob?.cancel()
                collectJob = scope.launch {
                    llmFlow.collect { event ->
                        when (event) {
                            is LlamaHelper.LLMEvent.Ongoing -> {
                                val token = event.word
                                response.append(token)
                                withContext(Dispatchers.Main) { onToken(token) }
                            }
                            is LlamaHelper.LLMEvent.Done -> {
                                latch.countDown()
                            }
                            is LlamaHelper.LLMEvent.Error -> {
                                generateError = Exception(event.message)
                                latch.countDown()
                            }
                            else -> {}
                        }
                    }
                }

                helper.predict(prompt)
                latch.await(180, TimeUnit.SECONDS)
                collectJob?.cancel()

                if (generateError != null) {
                    Result.failure(generateError!!)
                } else {
                    Result.success(response.toString())
                }

            } catch (e: Exception) {
                Log.e(TAG, "generate error: ${e.message}")
                Result.failure(e)
            }
        }
    }
    // Trong LlmEngine.kt
    fun stopGeneration() {
        try {
            llamaHelper?.stopPrediction()
            collectJob?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "stopGeneration: ${e.message}")
        }
    }

    fun unloadModel() {
        try {
            collectJob?.cancel()
            llamaHelper?.stopPrediction()  // ← đúng tên
            llamaHelper?.release()         // ← giải phóng context
        } catch (e: Exception) {
            Log.e(TAG, "unloadModel: ${e.message}")
        }
        llamaHelper = null
        currentModelId = null
        isModelLoaded = false
    }

    fun isLoaded(): Boolean = isModelLoaded && llamaHelper != null
    fun isNativeAvailable(): Boolean = true
    fun getCurrentModelId(): String? = currentModelId
}