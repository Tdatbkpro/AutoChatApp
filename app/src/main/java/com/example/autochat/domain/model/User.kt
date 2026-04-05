package com.example.autochat.domain.model

data class User(
    val id: String,
    val email : String,
    val username : String,
    val accessToken : String,
    val refreshToken : String
)