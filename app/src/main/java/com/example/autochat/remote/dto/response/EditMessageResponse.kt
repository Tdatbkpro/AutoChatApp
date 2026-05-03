package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class EditMessageResponse(
    @SerializedName("new_branch_id")   val newBranchId:   String,
    @SerializedName("user_message_id") val userMessageId: String,
    @SerializedName("bot_message_id")  val botMessageId:  String,
    @SerializedName("user_message")    val userMessage:   MessageResponse,
    @SerializedName("bot_message")     val botMessage:    MessageResponse,
    @SerializedName("branch_info")     val branchInfo:    BranchInfoResponse,
)