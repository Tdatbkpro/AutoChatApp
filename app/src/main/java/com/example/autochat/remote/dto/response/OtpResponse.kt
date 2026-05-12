package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class OtpResponse(
    @SerializedName("valid")
    val valid: Boolean,

    @SerializedName("message")
    val message: String?,

    @SerializedName("reset_token")  // ← Map snake_case → camelCase
    val resetToken: String?
)