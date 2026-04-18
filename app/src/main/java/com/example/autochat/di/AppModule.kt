package com.example.autochat.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.example.autochat.data.local.AppDatabase
import com.example.autochat.data.local.dao.ReadHistoryDao
import com.example.autochat.remote.api.AuthApi
import com.example.autochat.remote.api.ChatApi
import com.example.autochat.remote.api.RagApi
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
import javax.inject.Named
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "autochat_prefs")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ── DataStore ─────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        ctx.dataStore

    // ── Room Database ─────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(
            ctx,
            AppDatabase::class.java,
            "autochat.db"
        ).fallbackToDestructiveMigration().build()

    @Provides
    @Singleton
    fun provideReadHistoryDao(db: AppDatabase): ReadHistoryDao = db.readHistoryDao()

    // ── OkHttpClient (dùng chung) ─────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Retrofit: server-chat port 8001 ───────────────────────────────────────

    @Provides
    @Singleton
    @Named("chat")
    fun provideChatRetrofit(): Retrofit {
        val client = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("http://192.168.1.118:8001/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    // ── Retrofit: server-rag port 8000 ────────────────────────────────────────

    @Provides
    @Singleton
    @Named("rag")
    fun provideRagRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("http://192.168.1.118:8000/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    // ── API interfaces ────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideAuthApi(@Named("chat") retrofit: Retrofit): AuthApi =
        retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideChatApi(@Named("chat") retrofit: Retrofit): ChatApi =
        retrofit.create(ChatApi::class.java)

    @Provides
    @Singleton
    fun provideRagApi(@Named("rag") retrofit: Retrofit): RagApi =
        retrofit.create(RagApi::class.java)

    // ── Repositories ──────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideAuthRepository(impl: AuthRepositoryImpl): AuthRepository = impl

    @Provides
    @Singleton
    fun provideChatRepository(impl: ChatRepositoryImpl): ChatRepository = impl

    // ── WebSocket ─────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideWebSocketManager(): WebSocketManager = WebSocketManager()
}