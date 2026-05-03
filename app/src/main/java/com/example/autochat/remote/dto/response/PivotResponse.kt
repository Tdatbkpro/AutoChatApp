package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class PivotResponse(
    @SerializedName("pivot_id")  val pivotId:   String,
    @SerializedName("branch_ids") val branchIds: List<BranchIdItem>,
)
data class BranchIdItem(
    @SerializedName("branch_id")  val branchId:  String,
    @SerializedName("created_at") val createdAt: Long,
)