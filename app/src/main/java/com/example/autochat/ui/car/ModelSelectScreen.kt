package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.llm.ModelManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelSelectScreen(
    carContext: CarContext,
    private val chatScreen: MyChatScreen
) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var models = listOf<ModelManager.ModelInfo>()
    private var isLoading = false
    private var loadingModelId: String? = null

    private val modelManager by lazy {
        EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            ChatEntryPoint::class.java
        ).modelManager()
    }

    private val llmEngineFactory by lazy {
        EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            ChatEntryPoint::class.java
        ).llmEngineFactory()
    }

    init {
        loadModels()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
        })
    }

    private fun loadModels() {
        scope.launch {
            models = withContext(Dispatchers.IO) {
                modelManager.getAllModels().filter { it.isDownloaded }
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        if (models.isEmpty() && !isLoading) {
            listBuilder.setNoItemsMessage("Chưa có model nào được tải xuống")
        }

        val activeEngine = llmEngineFactory.getActiveEngine()
        val activeModelId = activeEngine?.getCurrentModelId()

        models.forEach { model ->
            val isActive = model.id == activeModelId
            val isLoadingThis = loadingModelId == model.id

            val status = when {
                isLoadingThis -> "⏳ Đang tải..."
                isActive -> "✅ Đang dùng"
                else -> "${model.sizeMB}MB"
            }

            listBuilder.addItem(
                Row.Builder()
                    .setTitle(model.name)
                    .addText(model.description)
                    .addText(status)
                    .apply { if (!isActive) setBrowsable(true) }
                    .setOnClickListener {
                        if (isLoadingThis) return@setOnClickListener
                        if (isLoading) {
                            CarToast.makeText(carContext, "Đang tải model khác...", CarToast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (isActive) {
                            showModelOptions(model, activeModelId = true)
                        } else {
                            showModelOptions(model, activeModelId = false)
                        }
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle("🤖 Chọn Model AI")
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .build()
    }
    private fun showModelOptions(model: ModelManager.ModelInfo, activeModelId: Boolean) {
        screenManager.push(object : Screen(carContext) {
            override fun onGetTemplate(): Template {
                return MessageTemplate.Builder(
                    "${model.name}\n${model.description}\n${model.sizeMB}MB"
                )
                    .setTitle("Tùy chọn model")
                    .setHeaderAction(Action.BACK)
                    .apply {
                        if (!activeModelId) {
                            addAction(
                                Action.Builder()
                                    .setTitle("✅ Dùng model này")
                                    .setOnClickListener {
                                        screenManager.pop()
                                        loadModel(model.id)
                                    }
                                    .build()
                            )
                        }
                    }
                    .addAction(
                        Action.Builder()
                            .setTitle("🗑 Xóa model")
                            .setOnClickListener {
                                screenManager.pop()
                                showDeleteConfirm(model)
                            }
                            .build()
                    )
                    .build()
            }
        })
    }

    private fun showDeleteConfirm(model: ModelManager.ModelInfo) {
        screenManager.push(object : Screen(carContext) {
            override fun onGetTemplate(): Template {
                return MessageTemplate.Builder(
                    "Xóa model ${model.name}?\n\nSẽ phải tải lại nếu muốn dùng sau này."
                )
                    .setTitle("🗑 Xóa model")
                    .setHeaderAction(Action.BACK)
                    .addAction(
                        Action.Builder()
                            .setTitle("Hủy")
                            .setOnClickListener { screenManager.pop() }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("Xóa")
                            .setOnClickListener {
                                screenManager.pop()
                                deleteModel(model)
                            }
                            .build()
                    )
                    .build()
            }
        })
    }

    private fun deleteModel(model: ModelManager.ModelInfo) {
        scope.launch {
            try {
                val activeEngine = llmEngineFactory.getActiveEngine()
                if (activeEngine?.getCurrentModelId() == model.id) {
                    activeEngine.unloadModel()
                }
                withContext(Dispatchers.IO) {
                    if (model.id.startsWith("custom_")) {
                        modelManager.deleteCustomModel(model.id)
                    } else {
                        modelManager.deleteModel(model.id)
                    }
                }
                CarToast.makeText(carContext, "🗑 Đã xóa ${model.name}", CarToast.LENGTH_SHORT).show()
                loadModels()
            } catch (e: Exception) {
                CarToast.makeText(carContext, "❌ Lỗi xóa: ${e.message}", CarToast.LENGTH_SHORT).show()
            }
        }
    }
    private fun loadModel(modelId: String) {
        isLoading = true
        loadingModelId = modelId
        invalidate()

        scope.launch {
            try {
                val engine = llmEngineFactory.getEngine(modelId)
                val result = withContext(Dispatchers.IO) {
                    engine.loadModel(modelId)
                }
                if (result.isSuccess) {
                    CarToast.makeText(carContext, "✅ Đã tải model thành công", CarToast.LENGTH_LONG).show()
                } else {
                    CarToast.makeText(carContext, "❌ Lỗi tải model", CarToast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                CarToast.makeText(carContext, "❌ ${e.message}", CarToast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
                loadingModelId = null
                invalidate()
            }
        }
    }
}