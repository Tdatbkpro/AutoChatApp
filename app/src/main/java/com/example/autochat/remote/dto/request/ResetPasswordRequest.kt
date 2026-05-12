package com.example.autochat.remote.dto.request

import com.google.gson.annotations.SerializedName

data class ResetPasswordRequest (
    @SerializedName("email") val email : String,
    @SerializedName("reset_token") val resetToken: String,
    @SerializedName("new_password") val newPassword : String,
)