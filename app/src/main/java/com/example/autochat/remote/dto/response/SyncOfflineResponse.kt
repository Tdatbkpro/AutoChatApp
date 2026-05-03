package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class SyncOfflineResponse(
    @SerializedName("synced_count") val syncedCount: Int,
    @SerializedName("new_sessions") val newSessions: Int,
    @SerializedName("message")      val message: String
)