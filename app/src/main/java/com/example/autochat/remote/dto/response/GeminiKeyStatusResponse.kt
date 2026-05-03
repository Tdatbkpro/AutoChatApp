package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class GeminiKeyStatusResponse(
    @SerializedName("has_key") val hasKey: Boolean,
    @SerializedName("masked_key") val maskedKey: String?
)