package com.example.autochat.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.example.autochat.remote.api.AuthApi
import com.example.autochat.remote.api.ChatApi
import com.example.autochat.domain.repository.AuthRepository
import com.example.autochat.domain.repository.AuthRepositoryImpl
import com.example.autochat.domain.repository.ChatRepository
import com.example.autochat.domain.repository.ChatRepositoryImpl
import com.example.autochat.websocket.WebSocketManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "autochat_prefs")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        ctx.dataStore

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("http://10.177.243.218:8000/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides
    fun provideAuthApi(retrofit: Retrofit): AuthApi =
        retrofit.create(AuthApi::class.java)

    @Provides
    fun provideChatApi(retrofit: Retrofit): ChatApi =
        retrofit.create(ChatApi::class.java)

    @Provides
    @Singleton
    fun provideAuthRepository(impl: AuthRepositoryImpl): AuthRepository = impl

    @Provides
    @Singleton
    fun provideChatRepository(impl: ChatRepositoryImpl): ChatRepository = impl

    // ✅ Thêm provider cho WebSocketManager
    @Provides
    @Singleton
    fun provideWebSocketManager(): WebSocketManager {
        return WebSocketManager()
    }


}