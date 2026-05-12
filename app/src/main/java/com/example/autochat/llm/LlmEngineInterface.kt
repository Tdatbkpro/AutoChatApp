package com.example.autochat.llm

/**
 * Interface chung cho tất cả LLM engine (llama.cpp, ONNX, …).
 *
 * Bất kỳ engine nào cũng phải implement đủ 5 nhóm:
 *   - loadModel / unloadModel
 *   - generate
 *   - stopGeneration
 *   - trạng thái (isLoaded, getCurrentModelId, isNativeAvailable)
 */
interface LlmEngineInterface {

    // ── Load / Unload ─────────────────────────────────────────────────────────

    /**
     * Load model theo [modelId].
     * Suspend — có thể mất vài chục giây trên thiết bị yếu.
     * Trả về [Result.success(true)] nếu OK, [Result.failure] nếu lỗi.
     */
    suspend fun loadModel(modelId: String): Result<Boolean>

    /** Giải phóng native resource. Safe to call nhiều lần. */
    fun unloadModel()

    // ── Generate ──────────────────────────────────────────────────────────────

    /**
     * Sinh văn bản từ [prompt].
     * [onToken] được gọi trên Main thread cho mỗi token streaming.
     * Trả về toàn bộ response sau khi hoàn tất (hoặc bị stop).
     */
    suspend fun generate(
        prompt: String,
        onToken: (String) -> Unit,
    ): Result<String>

    // ── Control ───────────────────────────────────────────────────────────────

    /** Dừng generation đang chạy (non-blocking). */
    fun stopGeneration()

    // ── State ─────────────────────────────────────────────────────────────────

    fun isLoaded(): Boolean
    fun getCurrentModelId(): String?

    /**
     * Kiểm tra native lib / .so của engine đã sẵn sàng chưa.
     * Dùng để UI biết engine có thể dùng được không trước khi load model.
     */
    fun isNativeAvailable(): Boolean
}