package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class BranchInfoResponse(
    @SerializedName("branch_id")  val branchId:  String,
    @SerializedName("index")      val index:     Int,
    @SerializedName("total")      val total:     Int,
    @SerializedName("created_at") val createdAt: Long,
)