package com.example.autochat.di

import com.example.autochat.data.repository.ReadHistoryRepository
import com.example.autochat.domain.repository.AuthRepository
import com.example.autochat.domain.repository.ChatRepository
import com.example.autochat.llm.LlmEngineFactory
import com.example.autochat.llm.ModelManager
import com.example.autochat.tts.TTSManager
import com.example.autochat.ui.phone.adapter.com.example.autochat.token.EncryptedTokenStorage
import com.example.autochat.ui.phone.util.ThemePreference
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatEntryPoint {
    fun chatRepository(): ChatRepository
    fun authRepository(): AuthRepository
    fun readHistoryRepository(): ReadHistoryRepository
    fun modelManager(): ModelManager
    fun llmEngineFactory(): LlmEngineFactory
    fun tokenStorage(): EncryptedTokenStorage
    fun themePreference(): ThemePreference
}