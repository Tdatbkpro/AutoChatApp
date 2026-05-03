package com.example.autochat.ui.phone

import com.example.autochat.domain.model.CodeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton giữ trạng thái phân nhánh trong 1 session.
 * Key = pivotMessageId (id của message user bị edit).
 */
object BranchManager {

    data class BranchInfo(
        val branchId:  String,
        val index:     Int,
        val total:     Int,
        val createdAt: String,
    )

    data class BranchState(
        val branchMap:      Map<String, List<BranchInfo>> = emptyMap(),
        val currentIndex:   Map<String, Int>              = emptyMap(),
        val activeBranchId: String                        = "",
    )

    private val _state = MutableStateFlow(BranchState())
    val state: StateFlow<BranchState> = _state.asStateFlow()

    fun init(sessionId: String) {
        _state.value = BranchState(activeBranchId = sessionId)
    }

    fun onBranchCreated(
        pivotMessageId: String,
        newBranchInfo:  BranchInfo,
        allBranches:    List<BranchInfo>,
    ) {
        val current      = _state.value
        val updatedMap   = current.branchMap.toMutableMap()
        val updatedIndex = current.currentIndex.toMutableMap()

        // Nếu allBranches rỗng → tự build từ branchMap cũ + nhánh mới
        val branches = allBranches.ifEmpty {
            val existing = current.branchMap[pivotMessageId] ?: emptyList()
            if (existing.none { it.branchId == newBranchInfo.branchId }) {
                existing + newBranchInfo
            } else existing
        }

        updatedMap[pivotMessageId]   = branches
        updatedIndex[pivotMessageId] = newBranchInfo.index

        _state.value = current.copy(
            branchMap      = updatedMap,
            currentIndex   = updatedIndex,
            activeBranchId = newBranchInfo.branchId,
        )
    }

    /** @return branchId của nhánh cần load, null nếu không đổi */
    fun switchBranch(pivotMessageId: String, delta: Int): String? {
        val current  = _state.value
        val branches = current.branchMap[pivotMessageId] ?: return null
        val curIdx   = current.currentIndex[pivotMessageId] ?: 0
        val newIdx   = (curIdx + delta).coerceIn(0, branches.size - 1)
        if (newIdx == curIdx) return null

        val updatedIndex = current.currentIndex.toMutableMap()
        updatedIndex[pivotMessageId] = newIdx
        val newBranchId = branches[newIdx].branchId

        _state.value = current.copy(
            currentIndex   = updatedIndex,
            activeBranchId = newBranchId,
        )
        return newBranchId
    }

    fun getBranchesAt(pivotMessageId: String): List<BranchInfo> =
        _state.value.branchMap[pivotMessageId] ?: emptyList()

    fun getCurrentIndexAt(pivotMessageId: String): Int =
        _state.value.currentIndex[pivotMessageId] ?: 0

    // ✅ Kiểm tra đúng: > 1 nhánh mới hiện navigator
    fun hasBranches(pivotMessageId: String): Boolean =
        (_state.value.branchMap[pivotMessageId]?.size ?: 0) > 1

    fun getActiveBranchId(): String = _state.value.activeBranchId
}