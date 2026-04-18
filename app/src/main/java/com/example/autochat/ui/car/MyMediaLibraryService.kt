//package com.example.autochat.service
//
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.content.IntentFilter
//import android.os.Build
//import android.os.Bundle
//import android.os.Handler
//import android.os.Looper
//import android.util.Log
//import androidx.annotation.OptIn
//import androidx.media3.common.MediaItem
//import androidx.media3.common.MediaMetadata
//import androidx.media3.common.PlaybackException
//import androidx.media3.common.Player
//import androidx.media3.common.util.UnstableApi
//import androidx.media3.exoplayer.ExoPlayer
//import androidx.media3.session.LibraryResult
//import androidx.media3.session.MediaLibraryService
//import androidx.media3.session.MediaLibrarySession
//import androidx.media3.session.MediaSession
//import androidx.media3.session.SessionCommand
//import androidx.media3.session.SessionResult
//import com.example.autochat.AppState
//import com.example.autochat.di.ChatEntryPoint
//import com.example.autochat.remote.dto.response.QuickReplyItem
//import com.example.autochat.ui.car.MediaTtsHelper
//import com.google.common.collect.ImmutableList
//import com.google.common.util.concurrent.Futures
//import com.google.common.util.concurrent.ListenableFuture
//import com.google.common.util.concurrent.SettableFuture
//import dagger.hilt.android.EntryPointAccessors
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.SupervisorJob
//import kotlinx.coroutines.cancel
//import kotlinx.coroutines.flow.firstOrNull
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.runBlocking
//
//class MyMediaLibraryService : MediaLibraryService() {
//
//    companion object {
//        const val ROOT_ID = "root"
//        const val ITEM_MIC = "action_mic"
//        const val ITEM_PREFIX_QR = "qr_"
//
//        // Custom commands
//        const val COMMAND_START_VOICE = "START_VOICE"
//        const val COMMAND_STOP_TTS = "STOP_TTS"
//
//        // Extra keys
//        const val EXTRA_MEDIA_ID = "android.media.metadata.MEDIA_ID"
//    }
//
//    private var mediaLibrarySession: MediaLibrarySession? = null
//    private lateinit var player: Player
//    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
//    private val handler = Handler(Looper.getMainLooper())
//
//    private var quickReplies: List<QuickReplyItem> = emptyList()
//    private var lastVoiceResult = ""
//    private var lastVoiceTime = 0L
//
//    private lateinit var voiceResultReceiver: BroadcastReceiver
//    private lateinit var voiceStatusReceiver: BroadcastReceiver
//
//    private val ttsHelper by lazy {
//        MediaTtsHelper(applicationContext)
//    }
//
//    // ── Player Listener để xử lý các sự kiện playback ─────────────────────
//
//    private val playerListener = object : Player.Listener {
//        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
//            if (playWhenReady) {
//                // Người dùng nhấn Play
//                triggerVoice()
//            }
//        }
//
//        override fun onIsPlayingChanged(isPlaying: Boolean) {
//            if (!isPlaying) {
//                // Người dùng nhấn Pause
//                ttsHelper.stop()
//            }
//        }
//
//        override fun onPlayerError(error: PlaybackException) {
//            Log.e("MEDIA_SERVICE", "Player error: ${error.message}")
//        }
//    }
//
//    // ── MediaLibrarySession Callback Implementation ───────────────────────
//
//    private val librarySessionCallback = object : MediaLibrarySession.Callback {
//
//        @OptIn(UnstableApi::class)
//        override fun onConnect(
//            session: MediaSession,
//            controller: MediaSession.ControllerInfo
//        ): MediaSession.ConnectionResult {
//            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
//                .add(SessionCommand(COMMAND_START_VOICE, Bundle.EMPTY))
//                .add(SessionCommand(COMMAND_STOP_TTS, Bundle.EMPTY))
//                .build()
//
//            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
//                .setAvailableSessionCommands(sessionCommands)
//                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
//                .build()
//        }
//
//        override fun onPostConnect(
//            session: MediaSession,
//            controller: MediaSession.ControllerInfo
//        ) {
//            updateNowPlayingMeta("AI Chatbot", "Nhấn mic để hỏi AI")
//        }
//
//        override fun onCustomCommand(
//            session: MediaSession,
//            controller: MediaSession.ControllerInfo,
//            customCommand: SessionCommand,
//            args: Bundle
//        ): ListenableFuture<SessionResult> {
//            // Kiểm tra nếu command được gửi cho một media item cụ thể
//            val mediaItemId = args.getString(EXTRA_MEDIA_ID)
//
//            return Futures.immediateFuture(when (customCommand.customAction) {
//                COMMAND_START_VOICE -> {
//                    if (mediaItemId != null) {
//                        handleMediaItemClick(mediaItemId)
//                    } else {
//                        triggerVoice()
//                    }
//                    SessionResult(SessionResult.RESULT_SUCCESS)
//                }
//                COMMAND_STOP_TTS -> {
//                    ttsHelper.stop()
//                    SessionResult(SessionResult.RESULT_SUCCESS)
//                }
//                else -> SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
//            })
//        }
//
//        override fun onGetLibraryRoot(
//            session: MediaLibrarySession,
//            browser: MediaSession.ControllerInfo,
//            params: MediaLibraryService.LibraryParams?
//        ): ListenableFuture<LibraryResult<MediaItem>> {
//            val rootItem = MediaItem.Builder()
//                .setMediaId(ROOT_ID)
//                .setMediaMetadata(
//                    MediaMetadata.Builder()
//                        .setTitle("AI Chatbot")
//                        .build()
//                )
//                .build()
//
//            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
//        }
//
//        override fun onGetChildren(
//            session: MediaLibrarySession,
//            browser: MediaSession.ControllerInfo,
//            parentId: String,
//            page: Int,
//            pageSize: Int,
//            params: MediaLibraryService.LibraryParams?
//        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
//            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
//
//            scope.launch {
//                try {
//                    val items = buildMediaItems()
//                    future.set(LibraryResult.ofItemList(items, params))
//                } catch (e: Exception) {
//                    Log.e("MEDIA_SERVICE", "onGetChildren error: ${e.message}")
//                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
//                }
//            }
//
//            return future
//        }
//
//        override fun onGetItem(
//            session: MediaLibrarySession,
//            browser: MediaSession.ControllerInfo,
//            mediaId: String
//        ): ListenableFuture<LibraryResult<MediaItem>> {
//            return Futures.immediateFuture(
//                when {
//                    mediaId == ITEM_MIC -> {
//                        LibraryResult.ofItem(createMicMediaItem(), null)
//                    }
//                    mediaId.startsWith(ITEM_PREFIX_QR) -> {
//                        val item = findQuickReplyMediaItem(mediaId)
//                        if (item != null) {
//                            LibraryResult.ofItem(item, null)
//                        } else {
//                            LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
//                        }
//                    }
//                    else -> LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
//                }
//            )
//        }
//
//        override fun onSearch(
//            session: MediaLibrarySession,
//            browser: MediaSession.ControllerInfo,
//            query: String,
//            params: MediaLibraryService.LibraryParams?
//        ): ListenableFuture<LibraryResult<Void>> {
//            // Xử lý tìm kiếm bằng giọng nói
//            if (query.isNotBlank()) {
//                handler.post { handleVoiceInput(query) }
//            }
//            return Futures.immediateFuture(LibraryResult.ofVoid(params))
//        }
//
//        // Xử lý khi media item được chọn để phát
//        @OptIn(UnstableApi::class)
//        override fun onSetMediaItems(
//            session: MediaSession,
//            controller: MediaSession.ControllerInfo,
//            mediaItems: List<MediaItem>,
//            startIndex: Int,
//            startPositionMs: Long
//        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition?>? {
//            mediaItems.firstOrNull()?.let { mediaItem ->
//                handleMediaItemClick(mediaItem.mediaId)
//            }
//            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) as ListenableFuture<MediaSession.MediaItemsWithStartPosition?>?
//        }
//
//        // Xử lý các lệnh playback từ MediaController
//
//    }
//
//    // ── Lifecycle Methods ─────────────────────────────────────────────────
//
//    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
//        return mediaLibrarySession
//    }
//
//    override fun onCreate() {
//        super.onCreate()
//
//        // Tạo player với listener
//        player = ExoPlayer.Builder(this).build().apply {
//            addListener(playerListener)
//        }
//
//        // Tạo MediaLibrarySession
//        mediaLibrarySession = MediaLibrarySession.Builder(this, player, librarySessionCallback)
//            .build()
//
//        restoreUser()
//        registerVoiceReceivers()
//        loadQuickReplies()
//    }
//
//    override fun onDestroy() {
//        scope.cancel()
//        ttsHelper.shutdown()
//
//        mediaLibrarySession?.run {
//            player.removeListener(playerListener)
//            player.release()
//            release()
//            mediaLibrarySession = null
//        }
//
//        try {
//            unregisterReceiver(voiceResultReceiver)
//            unregisterReceiver(voiceStatusReceiver)
//        } catch (e: Exception) {
//            // Receiver might not be registered
//        }
//
//        super.onDestroy()
//    }
//
//    override fun onTaskRemoved(rootIntent: Intent?) {
//        stopSelf()
//        super.onTaskRemoved(rootIntent)
//    }
//
//    // ── User Restore ──────────────────────────────────────────────────────
//
//    private fun restoreUser() {
//        runBlocking {
//            try {
//                val authRepo = EntryPointAccessors
//                    .fromApplication(applicationContext, ChatEntryPoint::class.java)
//                    .authRepository()
//                val user = authRepo.getCurrentUserFlow().firstOrNull()
//                if (user != null && user.accessToken.isNotEmpty()) {
//                    AppState.accessToken = user.accessToken
//                    AppState.refreshToken = user.refreshToken
//                    AppState.currentUserId = user.id
//                    AppState.username = user.username
//                    Log.d("MEDIA_SERVICE", "User restored: ${user.id}")
//                }
//            } catch (e: Exception) {
//                Log.e("MEDIA_SERVICE", "restoreUser error: ${e.message}")
//            }
//        }
//    }
//
//    // ── Quick Replies ─────────────────────────────────────────────────────
//
//    private fun loadQuickReplies() {
//        scope.launch {
//            try {
//                val chatRepo = EntryPointAccessors
//                    .fromApplication(applicationContext, ChatEntryPoint::class.java)
//                    .chatRepository()
//                val items = chatRepo.getQuickReplies(location = "hà nội")
//                if (items.isNotEmpty()) {
//                    quickReplies = items
//                    mediaLibrarySession?.notifyChildrenChanged(ROOT_ID, items.size, null)
//                }
//            } catch (e: Exception) {
//                Log.w("MEDIA_SERVICE", "loadQuickReplies: ${e.message}")
//            }
//        }
//    }
//
//    // ── Build Media Items ─────────────────────────────────────────────────
//
//    private suspend fun buildMediaItems(): ImmutableList<MediaItem> {
//        val items = mutableListOf<MediaItem>()
//        items.add(createMicMediaItem())
//
//        val displayItems = if (quickReplies.isNotEmpty()) {
//            quickReplies.take(6).map { qr ->
//                createQuickReplyMediaItem(
//                    mediaId = "$ITEM_PREFIX_QR${qr.id ?: qr.category}",
//                    title = "${qr.icon ?: "📌"} ${qr.category}",
//                    subtitle = qr.label.take(60)
//                )
//            }
//        } else {
//            listOf(
//                "💰 Giá vàng hôm nay" to "gold",
//                "⛽ Giá xăng hôm nay" to "fuel",
//                "🌤️ Thời tiết hôm nay" to "weather",
//                "⚽ Tin thể thao" to "the-thao",
//                "💹 Tin kinh tế" to "kinh-doanh",
//                "📰 Tin thời sự" to "thoi-su"
//            ).map { (label, cat) ->
//                createQuickReplyMediaItem(
//                    mediaId = "$ITEM_PREFIX_QR$cat",
//                    title = label,
//                    subtitle = "Nhấn để hỏi ngay"
//                )
//            }
//        }
//
//        items.addAll(displayItems)
//        return ImmutableList.copyOf(items)
//    }
//
//    private fun createMicMediaItem(): MediaItem {
//        val metadata = MediaMetadata.Builder()
//            .setTitle("🎤 Nói chuyện với AI")
//            .setSubtitle("Nhấn để hỏi bất kỳ điều gì")
//            .build()
//
//        return MediaItem.Builder()
//            .setMediaId(ITEM_MIC)
//            .setMediaMetadata(metadata)
//            .build()
//    }
//
//    private fun createQuickReplyMediaItem(
//        mediaId: String,
//        title: String,
//        subtitle: String
//    ): MediaItem {
//        val metadata = MediaMetadata.Builder()
//            .setTitle(title)
//            .setSubtitle(subtitle)
//            .build()
//
//        return MediaItem.Builder()
//            .setMediaId(mediaId)
//            .setMediaMetadata(metadata)
//            .build()
//    }
//
//    private fun findQuickReplyMediaItem(mediaId: String): MediaItem? {
//        val catOrId = mediaId.removePrefix(ITEM_PREFIX_QR)
//
//        return when (catOrId) {
//            "gold" -> createQuickReplyMediaItem(mediaId, "💰 Giá vàng hôm nay", "Cập nhật giá vàng mới nhất")
//            "fuel" -> createQuickReplyMediaItem(mediaId, "⛽ Giá xăng hôm nay", "Cập nhật giá xăng dầu mới nhất")
//            "weather" -> createQuickReplyMediaItem(mediaId, "🌤️ Thời tiết hôm nay", "Dự báo thời tiết chi tiết")
//            "the-thao" -> createQuickReplyMediaItem(mediaId, "⚽ Tin thể thao", "Tin tức thể thao nóng hổi")
//            "kinh-doanh" -> createQuickReplyMediaItem(mediaId, "💹 Tin kinh tế", "Thông tin kinh tế mới nhất")
//            "thoi-su" -> createQuickReplyMediaItem(mediaId, "📰 Tin thời sự", "Tin tức thời sự trong nước")
//            else -> {
//                quickReplies.find { it.id?.toString() == catOrId }?.let { qr ->
//                    createQuickReplyMediaItem(
//                        mediaId = mediaId,
//                        title = "${qr.icon ?: "📌"} ${qr.category}",
//                        subtitle = qr.label.take(60)
//                    )
//                }
//            }
//        }
//    }
//
//    // ── Voice Receivers ───────────────────────────────────────────────────
//
//    private fun registerVoiceReceivers() {
//        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            RECEIVER_NOT_EXPORTED
//        } else 0
//
//        voiceResultReceiver = object : BroadcastReceiver() {
//            override fun onReceive(context: Context, intent: Intent) {
//                val text = intent.getStringExtra("voice_text") ?: return
//                val now = System.currentTimeMillis()
//                if (text == lastVoiceResult && now - lastVoiceTime < 2000) return
//                lastVoiceResult = text
//                lastVoiceTime = now
//                Log.d("MEDIA_SERVICE", "Voice result: $text")
//                handler.post { handleVoiceInput(text) }
//            }
//        }
//
//        voiceStatusReceiver = object : BroadcastReceiver() {
//            override fun onReceive(context: Context, intent: Intent) {
//                val status = intent.getStringExtra("status") ?: return
//                Log.d("MEDIA_SERVICE", "Voice status: $status")
//                when (status) {
//                    "LISTENING" -> updateNowPlayingMeta("🎤 Đang nghe...", "Hãy nói câu hỏi")
//                    "TIMEOUT" -> updateNowPlayingMeta("AI Chatbot", "Nhấn mic để hỏi AI")
//                }
//            }
//        }
//
//        registerReceiver(voiceResultReceiver, IntentFilter("com.example.autochat.VOICE_RESULT"), flags)
//        registerReceiver(voiceStatusReceiver, IntentFilter("com.example.autochat.VOICE_STATUS"), flags)
//    }
//
//    // ── Voice Trigger ─────────────────────────────────────────────────────
//
//    private fun triggerVoice() {
//        updateNowPlayingMeta("🎤 Đang nghe...", "Hãy nói câu hỏi của bạn")
//        val intent = Intent(this, VoiceService::class.java).apply {
//            action = VoiceService.ACTION_START
//        }
//        startForegroundService(intent)
//    }
//
//    // ── Handle Voice Input ────────────────────────────────────────────────
//
//    private fun handleVoiceInput(text: String) {
//        updateNowPlayingMeta("⏳ AI đang trả lời...", "\"$text\"")
//        scope.launch {
//            try {
//                val chatRepo = EntryPointAccessors
//                    .fromApplication(applicationContext, ChatEntryPoint::class.java)
//                    .chatRepository()
//                val result = chatRepo.sendMessage(
//                    sessionId = AppState.currentSession?.id,
//                    content = text,
//                    isFollowUp = false,
//                    null
//                )
//                val botText = result.botMessage.content
//                updateNowPlayingMeta("🤖 AI Chatbot", botText.take(60))
//                ttsHelper.speak(botText)
//            } catch (e: Exception) {
//                Log.e("MEDIA_SERVICE", "handleVoiceInput error: ${e.message}")
//                updateNowPlayingMeta("AI Chatbot", "Lỗi kết nối, thử lại sau")
//                ttsHelper.speak("Xin lỗi, có lỗi kết nối. Vui lòng thử lại.")
//            }
//        }
//    }
//
//    // ── Update Now Playing Meta ───────────────────────────────────────────
//
//    private fun updateNowPlayingMeta(title: String, subtitle: String) {
//        mediaLibrarySession?.player?.apply {
//            val metadata = MediaMetadata.Builder()
//                .setTitle(title)
//                .setSubtitle(subtitle)
//                .build()
//
//            setMediaItem(
//                MediaItem.Builder()
//                    .setMediaId("now_playing")
//                    .setMediaMetadata(metadata)
//                    .build()
//            )
//            prepare()
//            playWhenReady = false
//        }
//    }
//
//    // ── Handle Media Item Click ───────────────────────────────────────────
//
//    private fun handleMediaItemClick(mediaId: String) {
//        when {
//            mediaId == ITEM_MIC -> {
//                triggerVoice()
//            }
//            mediaId.startsWith(ITEM_PREFIX_QR) -> {
//                val catOrId = mediaId.removePrefix(ITEM_PREFIX_QR)
//                val query = when (catOrId) {
//                    "gold" -> "giá vàng hôm nay"
//                    "fuel" -> "giá xăng dầu hôm nay"
//                    "weather" -> "thời tiết hôm nay"
//                    "the-thao" -> "tin thể thao hôm nay"
//                    "kinh-doanh" -> "tin kinh tế hôm nay"
//                    "thoi-su" -> "tin thời sự hôm nay"
//                    else -> quickReplies.find { it.id?.toString() == catOrId }?.label ?: "tin tức hôm nay"
//                }
//                handleVoiceInput(query)
//            }
//        }
//    }
//}