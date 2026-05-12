package com.example.autochat.remote.dto.request

import com.google.gson.annotations.SerializedName

data class GoogleAuthRequest  (
    @SerializedName("id_token") val idToken : String,
)