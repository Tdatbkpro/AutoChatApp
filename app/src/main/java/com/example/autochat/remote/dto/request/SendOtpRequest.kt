package com.example.autochat.remote.dto.request

import com.google.gson.annotations.SerializedName

data class SendOtpRequest (
    @SerializedName("email") val email : String,
    @SerializedName("purpose") val purpose: String
)