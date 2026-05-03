package com.example.autochat.remote.dto.request

import com.google.gson.annotations.SerializedName

data class OfflineMessageSync(
    @SerializedName("id") val id: String,
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("content") val content: String,
    @SerializedName("sender") val sender: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("session_title") val sessionTitle: String? = null,
    @SerializedName("extra_data") val extraData: Map<String, Any?>? = null
)