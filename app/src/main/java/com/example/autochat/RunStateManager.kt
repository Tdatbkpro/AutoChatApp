package com.example.autochat

import com.example.autochat.domain.model.CodeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton giữ trạng thái run của từng code block.
 * Key = blockId (hash của language+code).
 * Mỗi block có một [RunState] riêng → đóng/mở bottom sheet không mất dữ liệu.
 */
object RunStateManager {

    data class RunState(
        val language: String           = "",
        val code: String               = "",
        val stdin: String              = "",
        val status: Status             = Status.Idle,
        val result: CodeResult?        = null,
        val time: String?              = null,
        val memory: Int?               = null
    )

    enum class Status { Idle, Running, Done }

    private val _states = MutableStateFlow<Map<String, RunState>>(emptyMap())
    val states: StateFlow<Map<String, RunState>> = _states.asStateFlow()

    fun getState(blockId: String): RunState =
        _states.value[blockId] ?: RunState()

    fun update(blockId: String, transform: RunState.() -> RunState) {
        val current = _states.value.toMutableMap()
        current[blockId] = (current[blockId] ?: RunState()).transform()
        _states.value = current
    }

    fun setRunning(blockId: String, language: String, code: String, stdin: String) {
        update(blockId) {
            copy(language = language, code = code, stdin = stdin, status = Status.Running, result = null)
        }
    }

    fun setResult(blockId: String, result: CodeResult) {
        val extra = if (result is CodeResult.Success) result else null
        update(blockId) {
            copy(
                status = Status.Done,
                result = result,
                time   = (result as? CodeResult.Success)?.time,
                memory = (result as? CodeResult.Success)?.memory
            )
        }
    }

    fun reset(blockId: String) {
        update(blockId) { copy(status = Status.Idle, result = null, time = null, memory = null) }
    }

    /** Tạo id ổn định từ language + code */
    fun blockId(language: String, code: String): String =
        (language + code).hashCode().toString()
}