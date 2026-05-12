package com.example.autochat.remote.dto.request

import com.google.gson.annotations.SerializedName

data class VerifyOtpRequest (
    @SerializedName("email") val email : String,
    @SerializedName("purpose") val purpose: String,
    val otp : String
)