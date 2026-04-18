package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class StructuredDataResponse(
    @SerializedName("has_data")
    val hasData: Boolean,

    val topic: String? = null,
    val text: String? = null
)