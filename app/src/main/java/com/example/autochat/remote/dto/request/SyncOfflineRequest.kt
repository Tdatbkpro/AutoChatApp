package com.example.autochat.remote.dto.request

import com.google.gson.annotations.SerializedName

data class SyncOfflineRequest(
    @SerializedName("messages") val messages: List<OfflineMessageSync>
)