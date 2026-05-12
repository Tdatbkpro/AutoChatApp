package com.example.autochat.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.example.autochat.data.local.AppDatabase
import com.example.autochat.data.local.dao.CustomModelDao
import com.example.autochat.data.local.dao.ReadHistoryDao
import com.example.autochat.remote.api.AuthApi
import com.example.autochat.remote.api.ChatApi
import com.example.autochat.remote.api.GeminiKeyApi
import com.example.autochat.remote.api.RagApi
import com.example.autochat.domain.repository.AuthRepository
import com.example.autochat.domain.repository.AuthRepositoryImpl
import com.example.autochat.domain.repository.ChatRepository
import com.example.autochat.domain.repository.ChatRepositoryImpl
import com.example.autochat.domain.repository.GeminiKeyRepository
import com.example.autochat.domain.repository.GeminiKeyRepositoryImpl
import com.example.autochat.llm.LlmEngine
import com.example.autochat.llm.LlmEngineInterface
import com.example.autochat.remote.api.Judge0Api
import com.example.autochat.token.TokenAuthenticator
import com.example.autochat.tts.TTSManager
import com.example.autochat.ui.phone.adapter.com.example.autochat.token.EncryptedTokenStorage
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

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        ctx.dataStore

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

    @Provides
    @Singleton
    fun provideCustomModelDao(db: AppDatabase): CustomModelDao = db.customModelDao()

    @Provides
    @Singleton
    fun provideOkHttp(tokenAuthenticator: TokenAuthenticator ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .authenticator(tokenAuthenticator)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    @Named("chat")
    fun provideChatRetrofit(
        client: OkHttpClient  // ← inject vào
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl("http://192.168.1.118:8001/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("rag")
    fun provideRagRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("http://192.168.1.118:8000/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

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

    @Provides
    @Singleton
    fun provideGeminiKeyApi(@Named("chat") retrofit: Retrofit): GeminiKeyApi =
        retrofit.create(GeminiKeyApi::class.java)

    @Provides
    @Singleton
    fun provideAuthRepository(impl: AuthRepositoryImpl): AuthRepository = impl

    @Provides
    @Singleton
    fun provideChatRepository(impl: ChatRepositoryImpl): ChatRepository = impl

    @Provides
    @Singleton
    fun provideGeminiKeyRepository(impl: GeminiKeyRepositoryImpl): GeminiKeyRepository = impl  // ✅ trong object

    @Provides
    @Singleton
    fun provideWebSocketManager(): WebSocketManager = WebSocketManager()

    @Provides
    @Singleton
    @Named("judge0")
    fun provideJudge0Retrofit(): Retrofit = Retrofit.Builder()
        .baseUrl("https://ce.judge0.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .client(
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        )
        .build()

    @Provides
    @Singleton
    fun provideJudge0Api(@Named("judge0") retrofit: Retrofit): Judge0Api =
        retrofit.create(Judge0Api::class.java)
    @Provides
    @Singleton
    @Named("ttsBaseUrl")
    fun provideTtsBaseUrl(): String = "http://192.168.118:8001"
    @Provides
    @Singleton
    fun provideLlmEngineInterface(engine: LlmEngine): LlmEngineInterface = engine

    // Thêm provider này
    @Provides
    @Singleton
    @Named("ttsOkHttpClient")
    fun provideTtsOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .callTimeout(0, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)   // ← 3 phút cho TTS inference
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Sửa provideTtsManager — inject client riêng
    @Provides
    @Singleton
    fun provideTtsManager(
        @ApplicationContext context: Context,
        @Named("ttsOkHttpClient") client: OkHttpClient,  // ← đổi ở đây
        @Named("ttsBaseUrl") baseUrl: String,
    ): TTSManager = TTSManager(context, client, baseUrl)

    @Provides
    @Singleton
    fun provideEncryptedTokenStorage(
        @ApplicationContext ctx: Context
    ): EncryptedTokenStorage = EncryptedTokenStorage(ctx)

}