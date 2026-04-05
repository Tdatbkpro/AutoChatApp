package com.example.autochat.di

import com.example.autochat.domain.repository.AuthRepository
import com.example.autochat.domain.repository.ChatRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatEntryPoint {
    fun chatRepository(): ChatRepository
    fun authRepository(): AuthRepository

}