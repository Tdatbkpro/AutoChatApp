package com.example.autochat.llm

import com.example.autochat.data.local.entity.MessageEntity

/**
 * Prompt builder cho từng model family.
 *
 * Nguyên tắc chung cho model nhỏ (< 3B) dùng offline:
 *  - System prompt NGẮN, RÕRÀNG, không dài dòng
 *  - Không yêu cầu format phức tạp (JSON, XML) → model nhỏ không tuân theo được
 *  - Ưu tiên tiếng Việt ngắn gọn
 *  - Với model có thinking (Qwen3, DeepSeek-R1): BẮT BUỘC tắt thinking
 *  - History: tối đa 6 lượt gần nhất để tránh overflow context
 */
object PromptBuilder {

    private const val MAX_HISTORY = 6

    // ── System prompts theo use-case ─────────────────────────────────────────

    /** Prompt chung cho hỏi đáp kỹ thuật + code — ngắn gọn cho model nhỏ */
    private fun systemPromptTech() = """Bạn là trợ lý lập trình. Quy tắc:
- Trả lời ngắn gọn, đúng trọng tâm
- Code phải đúng cú pháp, có comment ngắn
- Nếu không chắc, nói rõ
- KHÔNG lặp lại câu hỏi, KHÔNG giải thích dài dòng"""

    /** Prompt nhẹ hơn cho model rất nhỏ (TinyLlama, SmolLM ≤ 1B) */
    private fun systemPromptMini() =
        "Trợ lý AI. Trả lời ngắn gọn, chính xác. Hỗ trợ code và kỹ thuật."

    // ────────────────────────────────────────────────────────────────────────
    // ROUTER — tự detect model từ modelId
    // ────────────────────────────────────────────────────────────────────────

    fun build(
        modelId: String,
        history: List<MessageEntity>,
        currentMsg: String
    ): String {
        val id      = modelId.lowercase()
        val recent  = history.takeLast(MAX_HISTORY)

        return when {
            // Qwen3 (mới nhất — hỗ trợ /no_think)
            id.contains("qwen3")                           -> buildQwen3(recent, currentMsg)

            // Qwen2.5
            id.contains("qwen2") || id.contains("qwen")   -> buildQwen2(recent, currentMsg)

            // Gemma 2/3 (dùng <start_of_turn>)
            id.contains("gemma")                           -> buildGemma(recent, currentMsg)

            // Llama 3.x (dùng <|start_header_id|>)
            id.contains("llama-3") || id.contains("llama3") -> buildLlama3(recent, currentMsg)

            // TinyLlama (Llama 2 / Zephyr format)
            id.contains("tinyllama")                       -> buildTinyLlama(recent, currentMsg)

            // SmolLM2 (ChatML format như Qwen)
            id.contains("smollm") || id.contains("smol")  -> buildSmolLM(recent, currentMsg)

            // Phi-3 / Phi-4
            id.contains("phi")                             -> buildPhi(recent, currentMsg)

            // DeepSeek-R1 / V3 (thinking model — phải tắt thinking)
            id.contains("deepseek")                        -> buildDeepSeek(recent, currentMsg)

            // Mistral / Mixtral
            id.contains("mistral") || id.contains("mixtral") -> buildMistral(recent, currentMsg)

            // Fallback: ChatML (Qwen format — phổ biến nhất)
            else                                           -> buildQwen2(recent, currentMsg)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // QWEN 3  — ChatML + /no_think để tắt chain-of-thought
    // https://qwen.readthedocs.io/en/latest/inference/chat.html
    // ────────────────────────────────────────────────────────────────────────
    private fun buildQwen3(history: List<MessageEntity>, q: String) = buildString {
        append("<|im_start|>system\n${systemPromptTech()}\n<|im_end|>\n")
        appendChatML(history)
        // /no_think ở đầu user message → Qwen3 không sinh <think> block
        append("<|im_start|>user\n/no_think ${q.trim()}\n<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    // ────────────────────────────────────────────────────────────────────────
    // QWEN 2.5  — ChatML thuần
    // ────────────────────────────────────────────────────────────────────────
    private fun buildQwen2(history: List<MessageEntity>, q: String) = buildString {
        append("<|im_start|>system\n${systemPromptTech()}\n<|im_end|>\n")
        appendChatML(history)
        append("<|im_start|>user\n${q.trim()}\n<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    // ────────────────────────────────────────────────────────────────────────
    // GEMMA 2 / 3  — <start_of_turn> format
    // Gemma KHÔNG hỗ trợ system prompt trong nhiều phiên bản GGUF
    // → ghép system vào lượt user đầu tiên
    // ────────────────────────────────────────────────────────────────────────
    private fun buildGemma(history: List<MessageEntity>, q: String) = buildString {
        val sys = systemPromptTech()

        if (history.isEmpty()) {
            // Lần đầu: ghép system vào user message
            append("<start_of_turn>user\n$sys\n\n${q.trim()}<end_of_turn>\n")
        } else {
            // Có history: thêm system vào user message đầu tiên
            var systemInjected = false
            history.forEach { msg ->
                val role = if (msg.sender == "user") "user" else "model"
                val content = if (!systemInjected && msg.sender == "user") {
                    systemInjected = true
                    "$sys\n\n${msg.content.trim()}"
                } else msg.content.trim()
                append("<start_of_turn>$role\n$content<end_of_turn>\n")
            }
            if (!systemInjected) {
                append("<start_of_turn>user\n$sys\n\n${q.trim()}<end_of_turn>\n")
            } else {
                append("<start_of_turn>user\n${q.trim()}<end_of_turn>\n")
            }
        }
        append("<start_of_turn>model\n")
    }

    // ────────────────────────────────────────────────────────────────────────
    // LLAMA 3.x  — <|start_header_id|> format
    // ────────────────────────────────────────────────────────────────────────
    private fun buildLlama3(history: List<MessageEntity>, q: String) = buildString {
        append("<|begin_of_text|>")
        append("<|start_header_id|>system<|end_header_id|>\n\n${systemPromptTech()}<|eot_id|>")
        history.forEach { msg ->
            val role = if (msg.sender == "user") "user" else "assistant"
            append("<|start_header_id|>$role<|end_header_id|>\n\n${msg.content.trim()}<|eot_id|>")
        }
        append("<|start_header_id|>user<|end_header_id|>\n\n${q.trim()}<|eot_id|>")
        append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    // ────────────────────────────────────────────────────────────────────────
    // TINYLLAMA 1.1B  — Zephyr / HF Chat format  (<|system|>)
    // Model rất nhỏ → system prompt cực ngắn
    // ────────────────────────────────────────────────────────────────────────
    private fun buildTinyLlama(history: List<MessageEntity>, q: String) = buildString {
        append("<|system|>\n${systemPromptMini()}</s>\n")
        history.forEach { msg ->
            if (msg.sender == "user")
                append("<|user|>\n${msg.content.trim()}</s>\n")
            else
                append("<|assistant|>\n${msg.content.trim()}</s>\n")
        }
        append("<|user|>\n${q.trim()}</s>\n")
        append("<|assistant|>\n")
    }

    // ────────────────────────────────────────────────────────────────────────
    // SMOLLM2  — ChatML (giống Qwen, system ngắn vì model ≤ 1.7B)
    // ────────────────────────────────────────────────────────────────────────
    private fun buildSmolLM(history: List<MessageEntity>, q: String) = buildString {
        append("<|im_start|>system\n${systemPromptMini()}\n<|im_end|>\n")
        appendChatML(history)
        append("<|im_start|>user\n${q.trim()}\n<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    // ────────────────────────────────────────────────────────────────────────
    // PHI-3 / PHI-4  — <|user|> format (khác TinyLlama dù trông giống)
    // ────────────────────────────────────────────────────────────────────────
    private fun buildPhi(history: List<MessageEntity>, q: String) = buildString {
        append("<|system|>\n${systemPromptTech()}<|end|>\n")
        history.forEach { msg ->
            val role = if (msg.sender == "user") "user" else "assistant"
            append("<|$role|>\n${msg.content.trim()}<|end|>\n")
        }
        append("<|user|>\n${q.trim()}<|end|>\n")
        append("<|assistant|>\n")
    }

    // ────────────────────────────────────────────────────────────────────────
    // DEEPSEEK-R1 / V3  — ChatML + prefix để tắt thinking
    // R1 dùng <think>...</think> block — PHẢI bắt đầu assistant bằng </think>
    // để skip thinking ngay lập tức
    // ────────────────────────────────────────────────────────────────────────
    private fun buildDeepSeek(history: List<MessageEntity>, q: String) = buildString {
        append("<|im_start|>system\n${systemPromptTech()}\n<|im_end|>\n")
        appendChatML(history)
        append("<|im_start|>user\n${q.trim()}\n<|im_end|>\n")
        // Bắt đầu assistant bằng </think> → model skip thinking, trả lời trực tiếp
        append("<|im_start|>assistant\n</think>\n")
    }

    // ────────────────────────────────────────────────────────────────────────
    // MISTRAL / MIXTRAL  — [INST] format
    // ────────────────────────────────────────────────────────────────────────
    private fun buildMistral(history: List<MessageEntity>, q: String) = buildString {
        // Mistral v1 không có system token riêng → ghép vào [INST] đầu tiên
        val sys = systemPromptTech()

        if (history.isEmpty()) {
            append("[INST] $sys\n\n${q.trim()} [/INST]")
        } else {
            var systemInjected = false
            history.forEach { msg ->
                if (msg.sender == "user") {
                    val content = if (!systemInjected) {
                        systemInjected = true
                        "$sys\n\n${msg.content.trim()}"
                    } else msg.content.trim()
                    append("[INST] $content [/INST]")
                } else {
                    append(" ${msg.content.trim()} </s>")
                }
            }
            append("[INST] ${q.trim()} [/INST]")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun StringBuilder.appendChatML(history: List<MessageEntity>) {
        history.forEach { msg ->
            val role = if (msg.sender == "user") "user" else "assistant"
            append("<|im_start|>$role\n${msg.content.trim()}\n<|im_end|>\n")
        }
    }
}