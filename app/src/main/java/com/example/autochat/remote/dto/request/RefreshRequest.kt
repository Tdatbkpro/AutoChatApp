package com.example.autochat.remote.dto.request

import com.google.gson.annotations.SerializedName

data class RefreshRequest(@SerializedName("refresh_token") val refreshToken: String)
