package com.example.autochat.ui.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession.Builder
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.autochat.AppState
import com.example.autochat.R
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.domain.repository.AuthRepository
import com.example.autochat.domain.repository.ChatRepository
import com.example.autochat.service.VoiceService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri

@OptIn(UnstableApi::class)
class AutoChatMediaService : MediaLibraryService() {

    companion object {
        const val NOTIF_CHANNEL_ID = "autochat_media_channel"
        const val NOTIF_ID         = 1001

        // Root browse node ids
        const val ROOT_ID             = "ROOT"
        const val CHAT_ROOT_ID        = "CHAT_ROOT"
        const val SESSION_ROOT_ID     = "SESSION_ROOT"
        const val HISTORY_ROOT_ID     = "HISTORY_ROOT"
        const val SETTINGS_ROOT_ID    = "SETTINGS_ROOT"
        const val QUICK_REPLY_ROOT_ID = "QUICK_REPLY_ROOT"

        // ── FIX #2: Settings sub-nodes (browsable) ─────────────────────────
        const val SETTINGS_LOCALE_ROOT = "SETTINGS_LOCALE_ROOT"
        const val SETTINGS_SPEED_ROOT  = "SETTINGS_SPEED_ROOT"
        const val SETTINGS_PITCH_ROOT  = "SETTINGS_PITCH_ROOT"

        // Playable / browsable id prefixes
        const val PREFIX_CHAT_MSG       = "CHAT_MSG:"
        const val PREFIX_NEWS_DETAIL    = "NEWS_DETAIL:"
        const val PREFIX_NEWS_LIST      = "NEWS_LIST:"
        const val PREFIX_MSG_DETAIL     = "MSG_DETAIL:"
        const val PREFIX_HISTORY_DETAIL = "HISTORY_DETAIL:"
        const val PREFIX_QR_ITEM        = "QR_ITEM:"

        // ── FIX #2: Settings option prefixes ───────────────────────────────
        const val PREFIX_SETTING_LOCALE = "SETTING_LOCALE:"
        const val PREFIX_SETTING_SPEED  = "SETTING_SPEED:"
        const val PREFIX_SETTING_PITCH  = "SETTING_PITCH:"

        // Custom commands
        const val SESSION_CMD_VOICE_INPUT         = "CMD_VOICE_INPUT"
        const val SESSION_CMD_SEND_MESSAGE        = "CMD_SEND_MESSAGE"
        const val SESSION_CMD_STOP_TTS            = "CMD_STOP_TTS"
        const val SESSION_CMD_NAVIGATE            = "CMD_NAVIGATE"
        const val SESSION_CMD_NEW_CHAT            = "CMD_NEW_CHAT"
        const val SESSION_CMD_LOAD_SESSION        = "CMD_LOAD_SESSION"
        const val SESSION_CMD_DELETE_SESSION      = "CMD_DELETE_SESSION"
        const val SESSION_CMD_RENAME_SESSION      = "CMD_RENAME_SESSION"
        const val SESSION_CMD_TTS_SETTINGS        = "CMD_TTS_SETTINGS"
        const val SESSION_CMD_DELETE_MSG_PAIR     = "CMD_DELETE_MSG_PAIR"
        const val SESSION_CMD_SEND_QUICK_REPLY    = "CMD_SEND_QUICK_REPLY"
        const val SESSION_CMD_VOICE_WITH_CONTEXT  = "CMD_VOICE_WITH_CONTEXT"

        // Bundle keys
        const val KEY_MESSAGE_TEXT      = "message_text"
        const val KEY_BOT_MESSAGE       = "bot_message"
        const val KEY_NAV_QUERY         = "nav_query"
        const val KEY_NAV_DISPLAY_NAME  = "nav_display_name"
        const val KEY_SESSION_ID        = "session_id"
        const val KEY_NEW_TITLE         = "new_title"
        const val KEY_TTS_SPEED         = "tts_speed"
        const val KEY_TTS_PITCH         = "tts_pitch"
        const val KEY_TTS_LOCALE        = "tts_locale"
        const val KEY_USER_MSG_ID       = "user_msg_id"
        const val KEY_BOT_MSG_ID        = "bot_msg_id"
        const val KEY_ARTICLE_ID        = "article_id"
        const val KEY_ARTICLE_CATEGORY  = "article_category"
        const val KEY_QR_SLOT_INDEX     = "qr_slot_index"
        const val KEY_CONTEXT_BOT_MSG   = "context_bot_msg"

        const val RESULT_OK    = SessionResult.RESULT_SUCCESS
        const val RESULT_ERROR = SessionResult.RESULT_ERROR_UNKNOWN

        // ── FIX #1: Placeholder mediaId ────────────────────────────────────
        // Khi AA request play nhưng synthesis chưa xong, ta trả về item có
        // mediaId này để AA hiện đúng tên bài mới, tránh Now Playing cũ.
        const val PLACEHOLDER_MEDIA_ID = "PLACEHOLDER_LOADING"
    }

    // ── Dependencies ───────────────────────────────────────────────────────
    private val chatRepository: ChatRepository by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ChatEntryPoint::class.java)
            .chatRepository()
    }
    private val authRepository: AuthRepository by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ChatEntryPoint::class.java)
            .authRepository()
    }
    private val readHistoryRepository by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ChatEntryPoint::class.java)
            .readHistoryRepository()
    }

    // ── State ──────────────────────────────────────────────────────────────
    private lateinit var player      : AutoChatPlayer
    private lateinit var mediaSession: MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val messageManager    = ChatMessageManager()
    private val quickReplyManager = QuickReplyManager()
    private val sessionListManager= SessionListManager()

    private val handler = Handler(Looper.getMainLooper())

    private val audioCache = mutableMapOf<String, android.net.Uri>()

    // ── FIX #1: track current mediaId để phát hiện stale synthesis ─────────
    private var currentPlayingMediaId: String? = null

    private lateinit var voiceResultReceiver : BroadcastReceiver
    private lateinit var voiceStatusReceiver : BroadcastReceiver
    private lateinit var voicePartialReceiver: BroadcastReceiver

    private var lastVoiceResult = ""
    private var lastVoiceTime   = 0L

    // Search debounce: chỉ sendMessage khi query ổn định (không thay đổi trong 600ms)
    private var lastSearchQuery  = ""
    private var searchRunnable   : Runnable? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        restoreAuthState()
        player = AutoChatPlayer(this)
        mediaSession = Builder(this, player.exoPlayer, SessionCallbackImpl())
            .setId("autochat_session_${System.currentTimeMillis()}")
            .build()
        registerVoiceReceivers()
        startForeground(NOTIF_ID, buildNotification())
        loadInitialData()
        Log.d("MEDIA_SVC", "AutoChatMediaService created")
    }

    override fun onDestroy() {
        scope.cancel()
        unregisterVoiceReceivers()
        mediaSession.release()
        player.releasePlayer()
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = mediaSession

    // ── Notification ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL_ID, "AutoChat AI", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "AutoChat AI Assistant" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reply)
            .setContentTitle("AutoChat AI")
            .setContentText("Trợ lý AI đang chạy")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // ── Auth ───────────────────────────────────────────────────────────────

    private fun restoreAuthState() {
        runBlocking {
            try {
                val user = authRepository.getCurrentUserFlow().firstOrNull()
                if (user != null && user.accessToken.isNotEmpty()) {
                    AppState.accessToken   = user.accessToken
                    AppState.refreshToken  = user.refreshToken
                    AppState.currentUserId = user.id
                    AppState.username      = user.username
                } else {

                }
            } catch (e: Exception) {
                Log.e("MEDIA_SVC", "restoreAuth: ${e.message}")
            }
        }
    }

    // ── Initial load ───────────────────────────────────────────────────────

    private fun loadInitialData() {
        scope.launch {
            quickReplyManager.load(applicationContext, chatRepository, readHistoryRepository)
            sessionListManager.observe(chatRepository, scope) // ← observe liên tục
            AppState.currentSession?.let {
                messageManager.observeSession(it.id, chatRepository, scope) {
                    notifyAllNodes()
                }
            }
            mediaSession.notifyChildrenChanged(ROOT_ID, Int.MAX_VALUE, null)
            mediaSession.notifyChildrenChanged(HISTORY_ROOT_ID, Int.MAX_VALUE, null)
            mediaSession.notifyChildrenChanged(SESSION_ROOT_ID, Int.MAX_VALUE, null)
            mediaSession.notifyChildrenChanged(CHAT_ROOT_ID, Int.MAX_VALUE, null)
        }
    }

    // ── Voice receivers ────────────────────────────────────────────────────

    private fun registerVoiceReceivers() {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            RECEIVER_NOT_EXPORTED else 0

        voiceResultReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val text = intent.getStringExtra("voice_text") ?: return
                val now  = System.currentTimeMillis()
                if (text == lastVoiceResult && now - lastVoiceTime < 2000) return
                lastVoiceResult = text; lastVoiceTime = now
                val botCtx = intent.getStringExtra("bot_context")
                handler.post { handleVoiceResult(text, botCtx) }
            }
        }

        voicePartialReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val partial = intent.getStringExtra("partial_text") ?: return
                val extras = Bundle().apply { putString("partial_text", partial) }
                mediaSession.broadcastCustomCommand(SessionCommand("VOICE_PARTIAL", Bundle.EMPTY), extras)
            }
        }

        voiceStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getStringExtra("status") ?: return
                handler.post {
                    val extras = Bundle().apply { putString("status", status) }
                    val cmd = if (status == "TIMEOUT") "VOICE_TIMEOUT" else "VOICE_STATUS"
                    mediaSession.broadcastCustomCommand(SessionCommand(cmd, Bundle.EMPTY), extras)
                }
            }
        }

        applicationContext.registerReceiver(voiceResultReceiver,
            IntentFilter("com.example.autochat.VOICE_RESULT"), flags)
        applicationContext.registerReceiver(voiceStatusReceiver,
            IntentFilter("com.example.autochat.VOICE_STATUS"), flags)
        applicationContext.registerReceiver(voicePartialReceiver,
            IntentFilter("com.example.autochat.VOICE_PARTIAL"), flags)
    }

    private fun unregisterVoiceReceivers() {
        try { applicationContext.unregisterReceiver(voiceResultReceiver) } catch (_: Exception) {}
        try { applicationContext.unregisterReceiver(voiceStatusReceiver) } catch (_: Exception) {}
        try { applicationContext.unregisterReceiver(voicePartialReceiver) } catch (_: Exception) {}
    }

    private fun handleVoiceResult(text: String, botContext: String? = null) {
        sendMessage(text, botMessage = botContext)
    }

    // ── Send message ───────────────────────────────────────────────────────

    fun sendMessage(text: String, botMessage: String?) {
        if (messageManager.isSending) {
            Log.w("MEDIA_SVC", "Already sending: $text")
            return
        }
        messageManager.isSending      = true
        messageManager.isWaitingReply = true
        notifyAllNodes() // Notify ngay để hiện "đang trả lời..."

        scope.launch {
            try {
                val result = chatRepository.sendMessage(
                    sessionId  = AppState.currentSession?.id,
                    content    = text,
                    isFollowUp = botMessage != null,
                    botMessage
                )

                // Cập nhật session nếu mới
                val isNewSession = AppState.currentSession?.id != result.sessionId
                if (isNewSession) {
                    AppState.currentSession = ChatSession(
                        id        = result.sessionId,
                        userId    = AppState.currentUserId,
                        title     = result.sessionTitle,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    // Await load session list trước
                    sessionListManager.reload(chatRepository)
                    // Rồi mới observe messages
                    // Trong sendMessage, thay observeSession call:
                    messageManager.observeSession(result.sessionId, chatRepository, scope) {
                        notifyAllNodes() // ← thêm ROOT_ID vào đây
                    }
                }

                messageManager.isSending      = false
                messageManager.isWaitingReply = false

                val botMsg = result.botMessage.copy(sessionId = result.sessionId)
                val type   = botMsg.extraData?.get("type") as? String

                when (type) {
                    "news_list" -> {
                        notifyAllNodes()
                        notifyNewsListChanged(botMsg.id)
                    }
                    "navigation" -> {
                        val navData   = botMsg.extraData["navigation"] as? Map<*, *>
                        val navQuery  = navData?.get("nav_query") as? String
                        val target    = navData?.get("target") as? String
                        if (navQuery != null && target != null) {
                            player.speakText(botMsg.content)
                            // Mở Maps ngay như MyChatScreen.navigateTo()
                            startNavigation(navQuery, target)
                            val extras = Bundle().apply {
                                putString(KEY_NAV_QUERY, navQuery)
                                putString(KEY_NAV_DISPLAY_NAME, target)
                            }
                            mediaSession.broadcastCustomCommand(
                                SessionCommand("NAV_REQUEST", Bundle.EMPTY), extras)
                        }
                        notifyAllNodes()
                    }
                    else -> {
                        player.speakText(botMsg.content)
                        notifyAllNodes()
                    }
                }

                // Notify search result nếu đang có query
                if (lastSearchQuery.isNotEmpty()) {
                    notifySearchResultChangedForAllControllers(lastSearchQuery)
                }

            } catch (e: Exception) {
                Log.e("MEDIA_SVC", "sendMessage: ${e.message}")
                messageManager.isSending      = false
                messageManager.isWaitingReply = false
                notifyAllNodes()
                if (lastSearchQuery.isNotEmpty()) {
                    notifySearchResultChangedForAllControllers(lastSearchQuery)
                }
            }
        }
    }
    private fun notifyAllNodes() {
        mediaSession.notifyChildrenChanged(ROOT_ID,          Int.MAX_VALUE, null)
        mediaSession.notifyChildrenChanged(CHAT_ROOT_ID,     Int.MAX_VALUE, null)
        mediaSession.notifyChildrenChanged(SESSION_ROOT_ID,  Int.MAX_VALUE, null)
        mediaSession.notifyChildrenChanged(HISTORY_ROOT_ID,  Int.MAX_VALUE, null)
    }

    // ── Notifications ──────────────────────────────────────────────────────

    private fun notifyCurrentChatChanged() = notifyAllNodes()

    /**
     * Notify tất cả connected controllers về search result mới.
     * MediaLibrarySession.notifySearchResultChanged cần ControllerInfo cụ thể,
     * nên ta notify qua connectedControllers.
     */
    private fun notifySearchResultChangedForAllControllers(query: String) {
        try {
            mediaSession.connectedControllers.forEach { controller ->
                mediaSession.notifySearchResultChanged(controller, query, Int.MAX_VALUE, null)
            }
        } catch (e: Exception) {
            Log.w("MEDIA_SVC", "notifySearchResult: ${e.message}")
        }
    }

    private fun notifyNewsListChanged(msgId: String) {
        mediaSession.notifyChildrenChanged("$PREFIX_NEWS_LIST$msgId", Int.MAX_VALUE, null)
        notifyCurrentChatChanged()
    }

    // ══════════════════════════════════════════════════════════════════════
    // MediaLibrarySession.Callback
    // ══════════════════════════════════════════════════════════════════════
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            "android.media.action.MEDIA_PLAY_FROM_SEARCH" -> {
                val query = intent.getStringExtra("query") ?: ""
                if (query.isNotBlank()) {
                    handler.post { sendMessage(query, null) }
                }
            }
        }
        return START_STICKY
    }
    inner class SessionCallbackImpl : MediaLibrarySession.Callback {

        // ── Connection ────────────────────────────────────────────────────

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {

            val customLayout = ImmutableList.of(
                CommandButton.Builder()
                    .setDisplayName("🎤 Hỏi về bài này")
                    .setIconResId(R.drawable.ic_mic)
                    .setSessionCommand(SessionCommand(SESSION_CMD_VOICE_WITH_CONTEXT, Bundle.EMPTY))
                    .build(),
                CommandButton.Builder()
                    .setDisplayName("💬 Chat mới")
                    .setIconResId(R.drawable.ic_add)
                    .setSessionCommand(SessionCommand(SESSION_CMD_NEW_CHAT, Bundle.EMPTY))
                    .build(),
                CommandButton.Builder()
                    .setDisplayName("⏹ Dừng")
                    .setIconResId(R.drawable.ic_reply)
                    .setPlayerCommand(Player.COMMAND_STOP)
                    .build()
            )

            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(SESSION_CMD_VOICE_INPUT,        Bundle.EMPTY))
                .add(SessionCommand(SESSION_CMD_VOICE_WITH_CONTEXT, Bundle.EMPTY))
                .add(SessionCommand(SESSION_CMD_SEND_MESSAGE,       Bundle.EMPTY))
                .add(SessionCommand(SESSION_CMD_STOP_TTS,           Bundle.EMPTY))
                .add(SessionCommand(SESSION_CMD_NAVIGATE,           Bundle.EMPTY))
                .add(SessionCommand(SESSION_CMD_NEW_CHAT,           Bundle.EMPTY))
                .add(SessionCommand(SESSION_CMD_LOAD_SESSION,       Bundle.EMPTY))
                .add(SessionCommand(SESSION_CMD_DELETE_SESSION,     Bundle.EMPTY))
                .add(SessionCommand(SESSION_CMD_RENAME_SESSION,     Bundle.EMPTY))
                .add(SessionCommand(SESSION_CMD_TTS_SETTINGS,       Bundle.EMPTY))
                .add(SessionCommand(SESSION_CMD_DELETE_MSG_PAIR,    Bundle.EMPTY))
                .add(SessionCommand(SESSION_CMD_SEND_QUICK_REPLY,   Bundle.EMPTY))
                .build()

            val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(Player.COMMAND_GET_TIMELINE)
                        .build()
                )
                .setCustomLayout(customLayout)
                .build()

            // Mồi search subscription để Hey Google nhận input ngay không cần tap
            handler.postDelayed({
                try {
                    mediaSession.connectedControllers
                        .firstOrNull { it == controller }
                        ?.let { ctrl ->
                            mediaSession.notifySearchResultChanged(ctrl, " ", 1, null)
                        }
                } catch (_: Exception) {}
            }, 500)

            return result
        }

        // ── Browse: root ──────────────────────────────────────────────────

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(buildBrowsableItem(ROOT_ID, "AutoChat AI"), params)
        )

        // ── Browse: children ──────────────────────────────────────────────

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.d("MEDIA_SVC", "onGetChildren: $parentId p=$page ps=$pageSize")

            // HISTORY_DETAIL: suspend call → trả về SettableFuture, không dùng runBlocking
            if (parentId.startsWith(PREFIX_HISTORY_DETAIL)) {
                val sid    = parentId.removePrefix(PREFIX_HISTORY_DETAIL)
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                // Cập nhật AppState TRƯỚC khi load messages (trên Main)
                val targetSession = sessionListManager.sessions.firstOrNull { it.id == sid }
                if (targetSession != null && AppState.currentSession?.id != sid) {
                    AppState.currentSession = targetSession
                    messageManager.clear()
                    messageManager.observeSession(sid, chatRepository, scope) {
                        notifyCurrentChatChanged()
                        mediaSession.notifyChildrenChanged(SESSION_ROOT_ID, Int.MAX_VALUE, null)
                        mediaSession.notifyChildrenChanged(ROOT_ID, Int.MAX_VALUE, null)
                    }
                    notifyCurrentChatChanged()
                    mediaSession.notifyChildrenChanged(SESSION_ROOT_ID, Int.MAX_VALUE, null)
                    mediaSession.notifyChildrenChanged(ROOT_ID, Int.MAX_VALUE, null)
                }
                scope.launch(Dispatchers.IO) {
                    val items = buildHistoryDetailChildren(sid, page, pageSize)
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                }
                return future
            }

            val items = when {
                parentId == ROOT_ID              -> buildRootChildren()
                parentId == CHAT_ROOT_ID         -> buildChatChildren()
                parentId == SESSION_ROOT_ID      -> buildSessionChildren()
                parentId == QUICK_REPLY_ROOT_ID  -> buildQuickReplyChildren()
                parentId == HISTORY_ROOT_ID      -> buildHistoryChildren(page, pageSize)
                parentId == SETTINGS_ROOT_ID     -> buildSettingsChildren()
                parentId == SETTINGS_LOCALE_ROOT -> buildLocaleOptionItems()
                parentId == SETTINGS_SPEED_ROOT  -> buildSpeedOptionItems()
                parentId == SETTINGS_PITCH_ROOT  -> buildPitchOptionItems()
                parentId.startsWith(PREFIX_NEWS_LIST) -> {
                    val msgId = parentId.removePrefix(PREFIX_NEWS_LIST)
                    buildNewsListChildren(msgId)
                }
                else -> emptyList()
            }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            )
        }

        // ── Browse: single item ───────────────────────────────────────────

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d("MEDIA_SVC", "onGetItem: $mediaId")

            if (mediaId == ROOT_ID)
                return Futures.immediateFuture(LibraryResult.ofItem(buildBrowsableItem(ROOT_ID, "AutoChat AI"), null))

            // ── FIX #2: settings sub-roots ────────────────────────────────
            if (mediaId in listOf(
                    CHAT_ROOT_ID, SESSION_ROOT_ID, HISTORY_ROOT_ID,
                    SETTINGS_ROOT_ID, QUICK_REPLY_ROOT_ID,
                    SETTINGS_LOCALE_ROOT, SETTINGS_SPEED_ROOT, SETTINGS_PITCH_ROOT)) {
                val title = when (mediaId) {
                    CHAT_ROOT_ID         -> "💬 Chat"
                    SESSION_ROOT_ID      -> "📌 Phiên hiện tại"
                    HISTORY_ROOT_ID      -> "🕒 Lịch sử"
                    SETTINGS_ROOT_ID     -> "⚙️ Cài đặt"
                    QUICK_REPLY_ROOT_ID  -> "💬 Tin vắn"
                    SETTINGS_LOCALE_ROOT -> "🌐 Ngôn ngữ giọng đọc"
                    SETTINGS_SPEED_ROOT  -> "⏩ Tốc độ đọc"
                    SETTINGS_PITCH_ROOT  -> "🎵 Cao độ giọng"
                    else -> "AutoChat"
                }
                return Futures.immediateFuture(LibraryResult.ofItem(buildBrowsableItem(mediaId, title), null))
            }

            // ── FIX #2: setting option items ──────────────────────────────
            if (mediaId.startsWith(PREFIX_SETTING_LOCALE) ||
                mediaId.startsWith(PREFIX_SETTING_SPEED)  ||
                mediaId.startsWith(PREFIX_SETTING_PITCH)) {
                return Futures.immediateFuture(LibraryResult.ofItem(
                    buildPlayableItem(mediaId, mediaId, ""), null))
            }

            if (mediaId.startsWith(PREFIX_HISTORY_DETAIL)) {
                val sid     = mediaId.removePrefix(PREFIX_HISTORY_DETAIL)
                val s = sessionListManager.sessions.firstOrNull { it.id == sid }
                val item = MediaItem.Builder().setMediaId(mediaId)
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setTitle("💬 ${s?.title ?: "Chi tiết lịch sử"}")
                        .setSubtitle("Nhấn để xem tin nhắn")
                        .setIsBrowsable(true).setIsPlayable(false).build())
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(item, null))
            }

            if (mediaId.startsWith(PREFIX_NEWS_LIST))
                return Futures.immediateFuture(LibraryResult.ofItem(
                    buildBrowsableItem(mediaId, "📰 Danh sách tin tức", "Nhấn để xem chi tiết"), null))

            // Chat/history messages → synthesize on-demand
            val msg = findMessage(mediaId)
            if (msg != null) {
                val audioFile = File(cacheDir, "audio_${msg.id}.wav")
                val success   = player.synthesizeToFileSync(msg.content, audioFile)
                if (success && audioFile.exists() && audioFile.length() > 0) {
                    val uri  = FileProvider.getUriForFile(applicationContext, "${packageName}.provider", audioFile)
                    val item = MediaItem.Builder().setMediaId(mediaId).setUri(uri)
                        .setMediaMetadata(MediaMetadata.Builder()
                            .setTitle(if (msg.sender == "bot") "🤖 Bot" else "👤 Bạn")
                            .setSubtitle(msg.content)
                            .setIsPlayable(true).setIsBrowsable(false).build())
                        .build()
                    return Futures.immediateFuture(LibraryResult.ofItem(item, null))
                }
            }

            if (mediaId.startsWith(PREFIX_NEWS_DETAIL) || mediaId.startsWith(PREFIX_QR_ITEM))
                return Futures.immediateFuture(LibraryResult.ofItem(
                    buildPlayableItem(mediaId, "📰 Tin tức", "Đang tải..."), null))

            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        // ── Playback: resumption ───────────────────────────────────────────

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Log.d("MEDIA_SVC", "onPlaybackResumption → app activated by voice")
            handler.post {
                player.speakText("AI Chatbot đã sẵn sàng. Bạn muốn hỏi gì?")
            }
            // Dùng this@AutoChatMediaService.mediaSession (MediaLibrarySession) thay vì tham số mediaSession
            handler.postDelayed({
                try {
                    this@AutoChatMediaService.mediaSession.connectedControllers.forEach { ctrl ->
                        this@AutoChatMediaService.mediaSession.notifySearchResultChanged(ctrl, " ", 1, null)
                    }
                } catch (_: Exception) {}
            }, 1000)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    emptyList(), C.INDEX_UNSET, C.TIME_UNSET
                )
            )
        }

        // ── Playback: setMediaItems ────────────────────────────────────────

        /**
         * FIX #1: Khi AA request play, ta NGAY LẬP TỨC trả về một placeholder
         * MediaItem có đúng title/subtitle của bài mới → AA hiện Now Playing
         * đúng tên ngay lập tức, không còn bài cũ trong khi chờ synthesis.
         *
         * Sau khi synthesis xong, ta dùng player.exoPlayer.replaceMediaItem()
         * để thay URI thực vào mà không reset position/state.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {

            // ── PLAYLIST mode ──────────────────────────────────────────────
            if (mediaItems.size > 1) {
                Log.d("MEDIA_SVC", "Playlist: ${mediaItems.size} items")
                val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val resolved = mediaItems.mapNotNull { item ->
                            val aId = item.mediaId.removePrefix(PREFIX_NEWS_DETAIL).toIntOrNull()
                                ?: return@mapNotNull null
                            resolveNewsItemToMediaItem(item, aId)
                        }
                        future.set(if (resolved.isNotEmpty())
                            MediaSession.MediaItemsWithStartPosition(
                                resolved,
                                if (startIndex in 0 until resolved.size) startIndex else 0,
                                C.TIME_UNSET)
                        else
                            MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET))
                    } catch (e: Exception) {
                        Log.e("MEDIA_SVC", "Playlist build: ${e.message}", e)
                        future.set(MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET))
                    }
                }
                return future
            }

            // ── SINGLE item ────────────────────────────────────────────────
            val resolvedIndex = if (startIndex == C.INDEX_UNSET || startIndex < 0) 0 else startIndex
            val item   = mediaItems.getOrNull(resolvedIndex) ?: return Futures.immediateFuture(noItems())
            val mediaId = item.mediaId

            Log.d("MEDIA_SVC", "onSetMediaItems single: $mediaId")
            currentPlayingMediaId = mediaId

            // ── MSG / CHAT ─────────────────────────────────────────────────
            if (mediaId.startsWith(PREFIX_MSG_DETAIL) || mediaId.startsWith(PREFIX_CHAT_MSG)) {
                return handleMsgMediaItem(mediaId, item)
            }

            // ── QR_ITEM ────────────────────────────────────────────────────
            if (mediaId.startsWith(PREFIX_QR_ITEM)) {
                return handleQrMediaItem(mediaId)
            }

            // ── NEWS_DETAIL single ─────────────────────────────────────────
            if (mediaId.startsWith(PREFIX_NEWS_DETAIL)) {
                return handleNewsDetailMediaItem(mediaId, item)
            }

            // ── SESSION items ──────────────────────────────────────────────
            if (mediaId.startsWith("SESSION_")) {
                handleSessionItem(mediaId)
                return Futures.immediateFuture(noItems())
            }

            // ── FIX #2: Settings option → apply & feedback TTS ─────────────
            if (mediaId.startsWith(PREFIX_SETTING_LOCALE) ||
                mediaId.startsWith(PREFIX_SETTING_SPEED)  ||
                mediaId.startsWith(PREFIX_SETTING_PITCH)) {
                handleSettingOptionItem(mediaId)
                return Futures.immediateFuture(noItems())
            }

            return Futures.immediateFuture(noItems())
        }

        // ── Search: debounce → sendMessage khi query ổn định ─────────────
        // Xoá biến debounce cũ
// private var lastSearchQuery  = ""
// private var searchRunnable   : Runnable? = null

        private var lastSearchQuery = ""
        private var pendingSearchQuery = ""
        private var searchRunnable: Runnable? = null

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            Log.d("MEDIA_SVC", "onSearch: '$query'")
            if (query.isBlank()) return Futures.immediateFuture(LibraryResult.ofVoid())

            val trimmed = query.trim()
            pendingSearchQuery = trimmed

            // Huỷ timer cũ
            searchRunnable?.let { handler.removeCallbacks(it) }

            // Đặt timer mới — KHÔNG sendMessage ở đây
            // Chỉ notify AA để nó hiện spinner ngay lập tức
            // AA sẽ gọi onGetSearchResult → trả về "đang chờ input"
            // Khi query ổn định 800ms → mới thực sự gửi
            val runnable = object : Runnable {
                override fun run() {
                    if (trimmed != pendingSearchQuery) return
                    if (trimmed == lastSearchQuery) return
                    if (messageManager.isSending) {
                        handler.postDelayed(this, 500)  // this = Runnable object này
                        return
                    }

                    lastSearchQuery = trimmed
                    Log.d("MEDIA_SVC", "Search fire: '$trimmed'")

                    scope.launch {
                        sendMessage(trimmed, botMessage = null)
                        try {
                            mediaSession.connectedControllers.forEach { controller ->
                                mediaSession.notifySearchResultChanged(controller, trimmed, Int.MAX_VALUE, null)
                            }
                        } catch (e: Exception) { Log.w("MEDIA_SVC", "notify: ${e.message}") }
                    }
                }
            }
            searchRunnable = runnable
            handler.postDelayed(runnable, 800)

            // Trả về ngay → AA hiện spinner "Kết quả tìm kiếm cho..."
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = mutableListOf<MediaItem>()

            when {
                // Đang chờ debounce fire (query chưa được gửi)
                query == pendingSearchQuery && query != lastSearchQuery -> {
                    items.add(buildPlayableItem(
                        id       = "SEARCH_WAITING",
                        title    = "⏳ Đang chờ...",
                        subtitle = "\"$query\""
                    ))
                }
                // Đã gửi, đang chờ AI trả lời
                messageManager.isWaitingReply -> {
                    items.add(buildPlayableItem(
                        id       = "SEARCH_PROCESSING",
                        title    = "🤖 AI đang trả lời...",
                        subtitle = "\"$query\""
                    ))
                }
                // Có kết quả
                messageManager.messages.isNotEmpty() -> {
                    items.addAll(buildChatChildren().take(pageSize))
                }
                else -> {
                    items.add(buildPlayableItem(
                        id       = "SEARCH_HINT",
                        title    = "🔍 Hãy đặt câu hỏi",
                        subtitle = "Nói hoặc gõ để hỏi AutoChat AI"
                    ))
                }
            }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            )
        }
        // ── Custom commands ────────────────────────────────────────────────

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> =
            Futures.immediateFuture(handleCustomCommand(customCommand.customAction, args))
    }

    // ══════════════════════════════════════════════════════════════════════
    // onSetMediaItems helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * FIX #1: Xử lý MSG media item với placeholder pattern:
     *
     * Bước 1 → Tìm message, build placeholder MediaItem với đúng title/subtitle.
     *           Trả về future resolved NGAY với placeholder có dummy URI (1 byte silent WAV).
     *           AA sẽ hiện title mới ngay lập tức thay vì bài cũ.
     *
     * Bước 2 → Chạy synthesis trên IO thread. Khi xong, gọi
     *           exoPlayer.replaceMediaItem(0, realItem) để update URI mà không
     *           reset Now Playing state trên AA.
     */
    private fun handleMsgMediaItem(
        mediaId: String,
        item: MediaItem
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {

        val msgId = if (mediaId.startsWith(PREFIX_MSG_DETAIL))
            mediaId.removePrefix(PREFIX_MSG_DETAIL)
        else
            mediaId.removePrefix(PREFIX_CHAT_MSG)

        val msg = messageManager.historyMessages.values.flatten().firstOrNull { it.id == msgId }
            ?: messageManager.messages.firstOrNull { it.id == msgId }

        if (msg == null) {
            Log.w("MEDIA_SVC", "Message not found: $msgId")
            return Futures.immediateFuture(noItems())
        }

        val extraType = msg.extraData?.get("type") as? String
        if (extraType == "news_list") {
            val nodeId      = "$PREFIX_NEWS_LIST${msg.id}"
            mediaSession.notifyChildrenChanged(nodeId, Int.MAX_VALUE, null)
            val summaryText = msg.content.ifBlank { "Danh sách tin tức" }
            val sessionTitle= AppState.currentSession?.title ?: "AutoChat"
            val future      = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            CoroutineScope(Dispatchers.IO).launch {
                val uri = audioCache["news_summary_${msg.id}"]
                    ?: synthesizeAndCache("news_summary_${msg.id}", summaryText)
                if (uri != null)
                    future.set(MediaSession.MediaItemsWithStartPosition(
                        listOf(buildMediaItemWithUri(mediaId, uri, sessionTitle, summaryText)), 0, C.TIME_UNSET))
                else
                    future.set(noItems())
            }
            return future
        }

        val text = msg.content
        if (text.isBlank()) return Futures.immediateFuture(noItems())

        val sessionTitle = AppState.currentSession?.title
            ?: sessionListManager.sessions.firstOrNull { s ->
                messageManager.historyMessages[s.id]?.any { it.id == msgId } == true
            }?.title ?: "AutoChat"

        // ── Check cache trước → trả về ngay không cần placeholder ────────────
        audioCache[msgId]?.let { cached ->
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
                listOf(buildMediaItemWithUri(mediaId, cached, sessionTitle, text)), 0, C.TIME_UNSET))
        }

        // ── Chưa có cache → synthesis async, BLOCK future cho đến khi xong ───
        // Thay vì placeholder silent WAV (bị play xong rồi skip),
        // dùng SettableFuture và chỉ resolve khi có URI thật
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        val placeholderTitle = if (msg.sender == "bot") "🤖 $sessionTitle" else "👤 Bạn"

        CoroutineScope(Dispatchers.IO).launch {
            val uri = synthesizeAndCache(msgId, text)

            // Nếu user đã chuyển sang item khác thì bỏ qua
            if (currentPlayingMediaId != mediaId) {
                Log.d("MEDIA_SVC", "Stale synthesis, skip: $mediaId")
                future.set(noItems())
                return@launch
            }

            if (uri != null) {
                val realItem = buildMediaItemWithUri(
                    mediaId  = mediaId,
                    uri      = uri,
                    title    = placeholderTitle,
                    subtitle = text.take(80) + if (text.length > 80) "…" else ""
                )
                future.set(MediaSession.MediaItemsWithStartPosition(
                    listOf(realItem), 0, C.TIME_UNSET))
            } else {
                future.set(noItems())
            }
        }

        return future
    }

    /**
     * Tạo một WAV tối giản (44 bytes header + 0 byte PCM) làm placeholder.
     * ExoPlayer có thể load nó mà không crash.
     */
    private fun getSilentWavUri(): android.net.Uri {
        val silentFile = File(cacheDir, "silent_placeholder.wav")
        if (!silentFile.exists() || silentFile.length() < 44) {
            silentFile.outputStream().use { out ->
                val pcmSize = 0
                out.write("RIFF".toByteArray())
                out.write(intToLe(36 + pcmSize))
                out.write("WAVE".toByteArray())
                out.write("fmt ".toByteArray())
                out.write(intToLe(16))
                out.write(shortToLe(1))       // PCM
                out.write(shortToLe(1))       // mono
                out.write(intToLe(16000))     // sample rate
                out.write(intToLe(32000))     // byte rate
                out.write(shortToLe(2))       // block align
                out.write(shortToLe(16))      // bits per sample
                out.write("data".toByteArray())
                out.write(intToLe(pcmSize))
            }
        }
        return FileProvider.getUriForFile(applicationContext, "${packageName}.provider", silentFile)
    }

    private fun intToLe(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    private fun shortToLe(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun handleQrMediaItem(
        mediaId: String
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val idx = mediaId.removePrefix(PREFIX_QR_ITEM).toIntOrNull()
                val qr  = idx?.let { quickReplyManager.items.getOrNull(it) }

                if (qr == null) { future.set(noItems()); return@launch }

                if (qr.id == null) {
                    val query = when (qr.category.lowercase()) {
                        "gold"    -> "giá vàng hôm nay"
                        "fuel"    -> "giá xăng dầu hôm nay"
                        "weather" -> "thời tiết hôm nay"
                        else      -> qr.label
                    }
                    val structured = try { chatRepository.getStructuredData(query) } catch (_: Exception) { null }
                    val text = if (structured?.hasData == true && !structured.text.isNullOrBlank())
                        structured.text!!
                    else "Không có dữ liệu cho \"${qr.label}\" lúc này."

                    val cacheKey = "qr_structured_${qr.category}_${System.currentTimeMillis() / 60000}"
                    val uri = synthesizeAndCache(cacheKey, text)
                    future.set(if (uri != null)
                        MediaSession.MediaItemsWithStartPosition(
                            listOf(buildMediaItemWithUri(mediaId, uri, qr.label, text)), 0, C.TIME_UNSET)
                    else noItems())
                    return@launch
                }

                val articleId = qr.id
                val cacheKey  = "qr_article_$articleId"
                val cachedUri = audioCache[cacheKey]
                if (cachedUri != null) {
                    future.set(MediaSession.MediaItemsWithStartPosition(
                        listOf(buildMediaItemWithUri(mediaId, cachedUri, qr.label, qr.label)), 0, C.TIME_UNSET))
                    return@launch
                }

                val article = chatRepository.getArticleById(articleId)
                if (article == null) { future.set(noItems()); return@launch }

                val fullText = buildArticleText(article)
                val uri      = synthesizeAndCache(cacheKey, fullText)

                scope.launch {
                    try {
                        readHistoryRepository.markRead(articleId, article.title ?: "", qr.category)
                        if (idx != null) {
                            quickReplyManager.refreshSlot(idx, articleId, qr.category, chatRepository, readHistoryRepository)
                            notifyQuickRepliesChanged()
                        }
                    } catch (_: Exception) {}
                }

                future.set(if (uri != null)
                    MediaSession.MediaItemsWithStartPosition(
                        listOf(buildMediaItemWithUri(mediaId, uri, article.title ?: qr.label, fullText)),
                        0, C.TIME_UNSET)
                else noItems())

            } catch (e: Exception) {
                Log.e("MEDIA_SVC", "QR item: ${e.message}", e)
                future.set(noItems())
            }
        }
        return future
    }

    private fun handleNewsDetailMediaItem(
        mediaId: String,
        item: MediaItem
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {

        val clickedArticleId  = mediaId.removePrefix(PREFIX_NEWS_DETAIL).toIntOrNull()
        val clickedArtworkUri = item.mediaMetadata.artworkUri
        val future            = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val allMessages = messageManager.messages +
                        messageManager.historyMessages.values.flatten()

                val parentMsg = allMessages.firstOrNull { msg ->
                    val newsItems = msg.extraData?.get("news_items") as? List<*>
                    newsItems?.any { raw ->
                        (raw as? Map<*, *>)?.get("article_id")
                            ?.let { id -> (id as? Number)?.toInt() } == clickedArticleId
                    } == true
                }

                val newsItems: List<MediaItem> = if (parentMsg != null)
                    buildNewsListChildren(parentMsg.id)
                else
                    listOf(item)

                val clickedIndex = newsItems.indexOfFirst { it.mediaId == mediaId }.coerceAtLeast(0)

                if (currentPlayingMediaId != mediaId) {
                    Log.d("MEDIA_SVC", "Stale resolve, skipping: $mediaId")
                    future.set(noItems())
                    return@launch
                }

                val resolved = newsItems.mapNotNull { newsItem ->
                    val aId = newsItem.mediaId.removePrefix(PREFIX_NEWS_DETAIL).toIntOrNull()
                        ?: return@mapNotNull null
                    val artworkUri = newsItem.mediaMetadata.artworkUri
                        ?: if (aId == clickedArticleId) clickedArtworkUri else null
                    resolveNewsItemToMediaItem(newsItem, aId, artworkUri)
                }

                Log.d("MEDIA_SVC", "NEWS playlist: ${resolved.size}/${newsItems.size}, start=$clickedIndex")

                future.set(if (resolved.isNotEmpty())
                    MediaSession.MediaItemsWithStartPosition(resolved, clickedIndex, C.TIME_UNSET)
                else noItems())

            } catch (e: Exception) {
                Log.e("MEDIA_SVC", "NEWS detail: ${e.message}", e)
                future.set(noItems())
            }
        }
        return future
    }

    // ── FIX #2: Xử lý click vào setting option item ───────────────────────

    /**
     * FIX #2: User click vào một option trong danh sách (ví dụ SETTING_SPEED:1.5).
     * Ta apply setting tương ứng và speak feedback.
     */
    private fun handleSettingOptionItem(mediaId: String) {
        scope.launch {
            when {
                mediaId.startsWith(PREFIX_SETTING_LOCALE) -> {
                    val locale = mediaId.removePrefix(PREFIX_SETTING_LOCALE)
                    player.applySettings(locale = locale)
                    val label = if (locale == "vi-VN") "Tiếng Việt" else "English (US)"
                    player.speakText("Đã chuyển ngôn ngữ sang $label")
                    mediaSession.notifyChildrenChanged(SETTINGS_LOCALE_ROOT, Int.MAX_VALUE, null)
                    mediaSession.notifyChildrenChanged(SETTINGS_ROOT_ID, Int.MAX_VALUE, null)
                }
                mediaId.startsWith(PREFIX_SETTING_SPEED) -> {
                    val speed = mediaId.removePrefix(PREFIX_SETTING_SPEED).toFloatOrNull() ?: return@launch
                    player.applySettings(speed = speed)
                    player.speakText("Đã đặt tốc độ đọc ${speed}x")
                    mediaSession.notifyChildrenChanged(SETTINGS_SPEED_ROOT, Int.MAX_VALUE, null)
                    mediaSession.notifyChildrenChanged(SETTINGS_ROOT_ID, Int.MAX_VALUE, null)
                }
                mediaId.startsWith(PREFIX_SETTING_PITCH) -> {
                    val pitch = mediaId.removePrefix(PREFIX_SETTING_PITCH).toFloatOrNull() ?: return@launch
                    player.applySettings(pitch = pitch)
                    player.speakText("Đã đặt cao độ giọng ${pitch}x")
                    mediaSession.notifyChildrenChanged(SETTINGS_PITCH_ROOT, Int.MAX_VALUE, null)
                    mediaSession.notifyChildrenChanged(SETTINGS_ROOT_ID, Int.MAX_VALUE, null)
                }
            }
        }
    }

    // ── Resolve news item → MediaItem with URI ─────────────────────────────

    private suspend fun resolveNewsItemToMediaItem(
        newsItem  : MediaItem,
        articleId : Int,
        artworkUri: android.net.Uri? = newsItem.mediaMetadata.artworkUri
    ): MediaItem? {
        val cacheKey = "news_article_$articleId"
        val uri = audioCache[cacheKey] ?: run {
            val article  = chatRepository.getArticleById(articleId) ?: return null
            val fullText = buildArticleText(article)
            synthesizeAndCache(cacheKey, fullText)
        } ?: return null

        return MediaItem.Builder()
            .setMediaId(newsItem.mediaId)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(newsItem.mediaMetadata.title)
                    .setSubtitle(newsItem.mediaMetadata.subtitle)
                    .setArtworkUri(artworkUri)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()
    }

    // ── Custom command dispatcher ──────────────────────────────────────────

    private fun handleCustomCommand(action: String, args: Bundle): SessionResult {
        return when (action) {

            SESSION_CMD_VOICE_INPUT -> {
                startForegroundService(Intent(this, VoiceService::class.java)
                    .apply { this.action = VoiceService.ACTION_START })
                SessionResult(RESULT_OK)
            }

            // FIX #4 / mic button: start voice + attach botMessage context từ item đang phát
            SESSION_CMD_VOICE_WITH_CONTEXT -> {
                val botMsgContext = getCurrentPlayingBotContent()
                val intent = Intent(this, VoiceService::class.java).apply {
                    this.action = VoiceService.ACTION_START
                    if (botMsgContext != null) putExtra("bot_context", botMsgContext)
                }
                startForegroundService(intent)
                val extras = Bundle().apply {
                    putString(KEY_CONTEXT_BOT_MSG, botMsgContext ?: "")
                }
                mediaSession.broadcastCustomCommand(
                    SessionCommand("VOICE_CONTEXT_STARTED", Bundle.EMPTY), extras)
                SessionResult(RESULT_OK)
            }

            SESSION_CMD_SEND_MESSAGE -> {
                val text   = args.getString(KEY_MESSAGE_TEXT) ?: return SessionResult(RESULT_ERROR)
                val botMsg = args.getString(KEY_BOT_MESSAGE)
                scope.launch { sendMessage(text, botMsg) }
                SessionResult(RESULT_OK)
            }

            SESSION_CMD_STOP_TTS -> {
                player.stopSpeak(); SessionResult(RESULT_OK)
            }

            SESSION_CMD_NAVIGATE -> {
                val navQuery = args.getString(KEY_NAV_QUERY) ?: return SessionResult(RESULT_ERROR)
                val display  = args.getString(KEY_NAV_DISPLAY_NAME) ?: navQuery
                startNavigation(navQuery, display)
                SessionResult(RESULT_OK)
            }

            SESSION_CMD_NEW_CHAT -> {
                AppState.currentSession = null
                messageManager.clear()
                notifyCurrentChatChanged()
                mediaSession.notifyChildrenChanged(SESSION_ROOT_ID, Int.MAX_VALUE, null)
                mediaSession.notifyChildrenChanged(ROOT_ID, Int.MAX_VALUE, null)
                SessionResult(RESULT_OK)
            }

            // ── FIX #4 + FIX #3 search: load session → cập nhật AppState ──
            SESSION_CMD_LOAD_SESSION -> {
                val sessionId = args.getString(KEY_SESSION_ID) ?: return SessionResult(RESULT_ERROR)
                scope.launch {
                    val s = sessionListManager.sessions.firstOrNull { it.id == sessionId }
                    if (s != null) {
                        // FIX #4: Cập nhật AppState.currentSession TRƯỚC khi observeSession
                        AppState.currentSession = s
                        messageManager.clear()   // xoá messages của session cũ
                        // Observe messages của session mới → tự động fill messageManager.messages
                        messageManager.observeSession(sessionId, chatRepository, scope) {
                            // Callback khi messages đã update → notify CHAT_ROOT_ID
                            notifyCurrentChatChanged()
                            mediaSession.notifyChildrenChanged(SESSION_ROOT_ID, Int.MAX_VALUE, null)
                        }
                        // Notify ngay để hiện "đang tải..."
                        notifyCurrentChatChanged()
                        mediaSession.notifyChildrenChanged(SESSION_ROOT_ID, Int.MAX_VALUE, null)
                        mediaSession.notifyChildrenChanged(ROOT_ID, Int.MAX_VALUE, null)
                        Log.d("MEDIA_SVC", "Loaded session: ${s.title} (${s.id})")
                    }
                }
                SessionResult(RESULT_OK)
            }

            SESSION_CMD_DELETE_SESSION -> {
                val sessionId = args.getString(KEY_SESSION_ID) ?: return SessionResult(RESULT_ERROR)
                scope.launch {
                    try {
                        chatRepository.deleteSession(sessionId)
                        if (AppState.currentSession?.id == sessionId) {
                            AppState.currentSession = null
                            messageManager.clear()
                            notifyCurrentChatChanged()
                            mediaSession.notifyChildrenChanged(SESSION_ROOT_ID, Int.MAX_VALUE, null)
                        }
                        sessionListManager.load(chatRepository)
                        mediaSession.notifyChildrenChanged(HISTORY_ROOT_ID, Int.MAX_VALUE, null)
                    } catch (e: Exception) { Log.e("MEDIA_SVC", "deleteSession: ${e.message}") }
                }
                SessionResult(RESULT_OK)
            }

            SESSION_CMD_RENAME_SESSION -> {
                val sessionId = args.getString(KEY_SESSION_ID) ?: return SessionResult(RESULT_ERROR)
                val newTitle  = args.getString(KEY_NEW_TITLE)  ?: return SessionResult(RESULT_ERROR)
                scope.launch {
                    try {
                        chatRepository.updateSessionTitle(sessionId, newTitle)
                        // FIX #4: Nếu đang xem session này, cập nhật AppState
                        if (AppState.currentSession?.id == sessionId) {
                            AppState.currentSession = AppState.currentSession?.copy(title = newTitle)
                        }
                        sessionListManager.load(chatRepository)
                        mediaSession.notifyChildrenChanged(HISTORY_ROOT_ID, Int.MAX_VALUE, null)
                        mediaSession.notifyChildrenChanged(SESSION_ROOT_ID, Int.MAX_VALUE, null)
                        mediaSession.notifyChildrenChanged(ROOT_ID, Int.MAX_VALUE, null)
                    } catch (e: Exception) { Log.e("MEDIA_SVC", "renameSession: ${e.message}") }
                }
                SessionResult(RESULT_OK)
            }

            SESSION_CMD_TTS_SETTINGS -> {
                val speed  = args.getFloat(KEY_TTS_SPEED, -1f)
                val pitch  = args.getFloat(KEY_TTS_PITCH, -1f)
                val locale = args.getString(KEY_TTS_LOCALE)
                player.applySettings(
                    speed  = if (speed  > 0) speed  else null,
                    pitch  = if (pitch  > 0) pitch  else null,
                    locale = locale
                )
                mediaSession.notifyChildrenChanged(SETTINGS_ROOT_ID, Int.MAX_VALUE, null)
                mediaSession.notifyChildrenChanged(SETTINGS_LOCALE_ROOT, Int.MAX_VALUE, null)
                mediaSession.notifyChildrenChanged(SETTINGS_SPEED_ROOT, Int.MAX_VALUE, null)
                mediaSession.notifyChildrenChanged(SETTINGS_PITCH_ROOT, Int.MAX_VALUE, null)
                SessionResult(RESULT_OK)
            }

            SESSION_CMD_DELETE_MSG_PAIR -> {
                val sessionId = args.getString(KEY_SESSION_ID)  ?: return SessionResult(RESULT_ERROR)
                val userMsgId = args.getString(KEY_USER_MSG_ID) ?: return SessionResult(RESULT_ERROR)
                val botMsgId  = args.getString(KEY_BOT_MSG_ID)
                scope.launch {
                    try {
                        chatRepository.deleteMessagePair(sessionId, userMsgId, botMsgId)
                        notifyCurrentChatChanged()
                    } catch (e: Exception) { Log.e("MEDIA_SVC", "deleteMsg: ${e.message}") }
                }
                SessionResult(RESULT_OK)
            }

            SESSION_CMD_SEND_QUICK_REPLY -> {
                val articleId = args.getInt(KEY_ARTICLE_ID, -1)
                val category  = args.getString(KEY_ARTICLE_CATEGORY)
                val slotIndex = args.getInt(KEY_QR_SLOT_INDEX, 0)
                if (articleId > 0) scope.launch { handleQuickReplyArticle(articleId, category, slotIndex) }
                SessionResult(RESULT_OK)
            }

            else -> SessionResult(RESULT_ERROR)
        }
    }

    // ── Quick reply article ────────────────────────────────────────────────

    private suspend fun handleQuickReplyArticle(articleId: Int, category: String?, slotIndex: Int) {
        try {
            val article = chatRepository.getArticleById(articleId) ?: return
            val content = buildString {
                if (!article.description.isNullOrBlank()) append(article.description).append("\n\n")
                if (!article.content.isNullOrBlank()) append(article.content)
            }.ifBlank { article.title ?: "" }
            player.speakText("${article.title}. $content", title = article.title ?: "Tin vắn")
            readHistoryRepository.markRead(articleId, article.title ?: "", category)
            quickReplyManager.refreshSlot(slotIndex, articleId, category, chatRepository, readHistoryRepository)
            notifyQuickRepliesChanged()
        } catch (e: Exception) {
            Log.e("MEDIA_SVC", "handleQuickReplyArticle: ${e.message}")
        }
    }

    private fun notifyQuickRepliesChanged() {
        mediaSession.notifyChildrenChanged(QUICK_REPLY_ROOT_ID, Int.MAX_VALUE, null)
        mediaSession.notifyChildrenChanged(ROOT_ID, Int.MAX_VALUE, null)
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    private fun startNavigation(navQuery: String, displayName: String) {
        try {
            // Thử CarContext intent trước (nếu có CarContext)
            val geoIntent = Intent(Intent.ACTION_VIEW).apply {
                data  = "geo:0,0?q=${android.net.Uri.encode(navQuery)}".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(geoIntent)
            Log.d("MEDIA_SVC", "Navigation started: $navQuery")
        } catch (e: Exception) {
            Log.e("MEDIA_SVC", "startNavigation failed: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Browse tree builders
    // ══════════════════════════════════════════════════════════════════════

    private fun buildRootChildren(): List<MediaItem> {
        val hasMessages = messageManager.messages.isNotEmpty()
        return listOf(
            buildBrowsableItem(
                id    = if (hasMessages) CHAT_ROOT_ID else QUICK_REPLY_ROOT_ID,
                title = if (hasMessages) "💬 ${AppState.currentSession?.title ?: "Chat mới"}" else "💬 Tin vắn"
            ),
            buildBrowsableItem(SESSION_ROOT_ID, "📌 ${AppState.currentSession?.title ?: "Phiên chat"}"),
            buildBrowsableItem(HISTORY_ROOT_ID, "🕒 Lịch sử", iconResId = R.drawable.ic_chat),
            buildBrowsableItem(SETTINGS_ROOT_ID, "⚙️ Cài đặt giọng đọc")
        )
    }

    /**
     * FIX #4: CHAT_ROOT_ID hiện đúng messages của currentSession.
     * messageManager.messages được fill bởi observeSession(currentSession.id)
     * nên sau khi loadSession → observeSession chạy → messages update → notifyCurrentChatChanged.
     */
    private fun buildChatChildren(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        if (messageManager.isWaitingReply)
            items.add(buildPlayableItem("WAITING", "🤖 AI đang trả lời...", "⏳ Vui lòng chờ"))

        if (messageManager.messages.isEmpty() && AppState.currentSession != null) {
            items.add(buildPlayableItem(
                "CHAT_EMPTY",
                "📭 Chưa có tin nhắn",
                "Phiên: ${AppState.currentSession?.title}"
            ))
        }

        messageManager.messages.takeLast(10).forEach { msg ->
            val timeStr = formatTime(msg.timestamp)
            if (msg.sender == "bot") {
                val newsExtra = extractNewsExtraData(msg)
                if (newsExtra != null) {
                    val count = (newsExtra["news_items"] as? List<*>)?.size ?: 0
                    items.add(buildBrowsableItem("$PREFIX_NEWS_LIST${msg.id}",
                        "🤖 Bot • $timeStr", "📰 $count bài • Nhấn để xem danh sách"))
                } else {
                    items.add(buildPlayableItem("$PREFIX_MSG_DETAIL${msg.id}",
                        "🤖 Bot • $timeStr", msg.content))
                }
            } else {
                items.add(buildPlayableItem("$PREFIX_CHAT_MSG${msg.id}",
                    "👤 Bạn • $timeStr", msg.content))
            }
        }
        items.add(buildBrowsableItem(SETTINGS_ROOT_ID, "⚙️ Cài đặt giọng đọc"))
        return items
    }

    private fun buildSessionChildren(): List<MediaItem> {
        val items    = mutableListOf<MediaItem>()
        val session  = AppState.currentSession
        val msgCount = messageManager.messages.size

        if (session != null) {
            items.add(buildPlayableItem(
                id       = "SESSION_INFO",
                title    = "📌 ${session.title}",
                subtitle = "$msgCount tin nhắn • ${formatTime(System.currentTimeMillis())}"
            ))
            if (msgCount > 0) {
                items.add(buildBrowsableItem(
                    id       = CHAT_ROOT_ID,
                    title    = "💬 Xem tin nhắn",
                    subtitle = "Mở danh sách hội thoại"
                ))
            }
        } else {
            items.add(buildPlayableItem(
                id       = "SESSION_EMPTY",
                title    = "Chưa có phiên chat",
                subtitle = "Nhấn nút Mic để bắt đầu"
            ))
        }

        items.add(buildPlayableItem(
            id       = "SESSION_NEW_CHAT",
            title    = "➕ Chat mới",
            subtitle = "Xóa lịch sử và bắt đầu phiên mới"
        ))
        items.add(buildPlayableItem(
            id       = "SESSION_MIC",
            title    = "🎤 Ghi âm",
            subtitle = if (session != null) "Gửi tin nhắn vào phiên: ${session.title}"
            else "Gửi tin nhắn mới"
        ))

        return items
    }

    private fun buildQuickReplyChildren(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val qrList = quickReplyManager.items

        if (qrList.isNotEmpty()) {
            qrList.take(6).forEachIndexed { idx, qr ->
                val title = buildString {
                    if (qr.icon.isNotEmpty()) append("${qr.icon} ")
                    append(if (qr.id != null) qr.category else qr.label)
                }
                val subtitle = if (qr.id != null) qr.label
                else when (qr.category.lowercase()) {
                    "gold"    -> "Giá vàng trong nước & thế giới"
                    "fuel"    -> "Giá xăng dầu mới nhất"
                    "weather" -> "Dự báo thời tiết hôm nay"
                    else      -> qr.label
                }
                val extras = Bundle().apply {
                    putInt(KEY_ARTICLE_ID, qr.id ?: -1)
                    putString(KEY_ARTICLE_CATEGORY, qr.category)
                    putInt(KEY_QR_SLOT_INDEX, idx)
                }
                items.add(buildPlayableItem("$PREFIX_QR_ITEM$idx", title, subtitle, extras))
            }
        } else {
            listOf(
                "💰 Giá vàng hôm nay" to "gold",
                "⛽ Giá xăng hôm nay" to "fuel",
                "🌤️ Thời tiết hôm nay" to "weather",
                "⚽ Tin thể thao"      to "the-thao",
                "💹 Tin kinh tế"       to "kinh-doanh",
                "📰 Tin thời sự"       to "thoi-su"
            ).forEachIndexed { idx, (label, category) ->
                items.add(buildPlayableItem("$PREFIX_QR_ITEM$idx", label, category))
            }
        }
        items.add(buildBrowsableItem(SETTINGS_ROOT_ID, "⚙️ Cài đặt giọng đọc"))
        return items
    }

    private fun buildHistoryChildren(page: Int, pageSize: Int): List<MediaItem> {
        val sessions = sessionListManager.sessions
        val start    = page * pageSize
        val end      = minOf(start + pageSize, sessions.size)
        return if (sessions.isEmpty())
            listOf(buildPlayableItem("HIST_EMPTY", "Chưa có lịch sử chat nào", ""))
        else
            sessions.subList(start, end).map { s ->
                // FIX #4: History item là browsable → khi click vào browse → trigger
                // loadSession qua SESSION_CMD_LOAD_SESSION.
                // Ta build item có mediaId = PREFIX_HISTORY_DETAIL + sessionId
                // Khi AA browse vào node này, onGetChildren sẽ gọi buildHistoryDetailChildren
                // VÀ ta intercept setMediaItems để gọi SESSION_CMD_LOAD_SESSION.
                buildBrowsableItem(
                    id       = "$PREFIX_HISTORY_DETAIL${s.id}",
                    title    = "💬 ${s.title}",
                    subtitle = formatTime(s.updatedAt)
                )
            }
    }

    // Chạy trên Dispatchers.IO. AppState update đã được xử lý trong onGetChildren
    private suspend fun buildHistoryDetailChildren(
        sessionId: String, page: Int, pageSize: Int
    ): List<MediaItem> {
        return try {
            val msgs = chatRepository.getMessages(sessionId)
            if (msgs.isEmpty()) return listOf(buildPlayableItem("HD_EMPTY", "Không có tin nhắn", ""))

            messageManager.historyMessages[sessionId] = msgs
            val start   = page * pageSize
            val end     = minOf(start + pageSize, msgs.size)
            val subList = msgs.subList(start, end)

            val items = subList.map { msg ->
                val timeStr   = formatTime(msg.timestamp)
                val extraType = msg.extraData?.get("type") as? String
                if (msg.sender == "bot" && extraType == "news_list") {
                    val count = (msg.extraData?.get("news_items") as? List<*>)?.size ?: 0
                    buildBrowsableItem("$PREFIX_NEWS_LIST${msg.id}",
                        "🤖 Bot • $timeStr", "📰 $count bài • Nhấn để xem")
                } else {
                    val mid = if (msg.sender == "bot") "$PREFIX_MSG_DETAIL${msg.id}"
                    else "$PREFIX_CHAT_MSG${msg.id}"
                    buildPlayableItem(mid,
                        if (msg.sender == "bot") "🤖 Bot • $timeStr" else "👤 Bạn • $timeStr",
                        msg.content)
                }
            }

            scope.launch(Dispatchers.IO) {
                subList.filter { msg -> msg.extraData?.get("type") as? String != "news_list" }
                    .forEach { msg -> synthesizeAndCache(msg.id, msg.content) }
            }
            items
        } catch (e: Exception) {
            Log.e("MEDIA_SVC", "buildHistoryDetailChildren: ${e.message}")
            listOf(buildPlayableItem("HD_ERROR", "Lỗi: ${e.message}", ""))
        }
    }

    private fun buildNewsListChildren(msgId: String): List<MediaItem> {
        val msg = messageManager.messages.firstOrNull { it.id == msgId }
            ?: messageManager.historyMessages.values.flatten().firstOrNull { it.id == msgId }
            ?: return emptyList()

        val extra    = extractNewsExtraData(msg) ?: return emptyList()
        val rawItems = extra["news_items"] as? List<*> ?: return emptyList()

        return rawItems.mapIndexedNotNull { idx, raw ->
            val it         = raw as? Map<*, *> ?: return@mapIndexedNotNull null
            val title      = it["title"] as? String ?: "Không có tiêu đề"
            val desc       = it["description"] as? String ?: ""
            val articleId  = (it["article_id"] as? Number)?.toInt()

            @Suppress("UNCHECKED_CAST")
            val firstImage = (it["media_items"] as? List<*>)
                ?.filterIsInstance<Map<*, *>>()
                ?.firstOrNull { m -> m["type"] == "image" }
                ?.get("url") as? String

            val extras = Bundle().apply {
                putInt(KEY_ARTICLE_ID, articleId ?: -1)
                if (firstImage != null) putString("artwork_url", firstImage)
            }

            val metaBuilder = MediaMetadata.Builder()
                .setTitle("${idx + 1}. $title")
                .setSubtitle(desc)
                .setIsPlayable(true).setIsBrowsable(false)
                .setExtras(extras)
            if (firstImage != null) metaBuilder.setArtworkUri(firstImage.toUri())

            MediaItem.Builder()
                .setMediaId("$PREFIX_NEWS_DETAIL${articleId ?: idx}")
                .setMediaMetadata(metaBuilder.build())
                .build()
        }
    }

    // ── FIX #2: Settings children → browsable nodes ───────────────────────

    /**
     * FIX #2: Thay vì playable cycle, mỗi setting là một browsable node
     * trỏ tới danh sách các option. User browse vào → thấy danh sách →
     * click option → apply & TTS feedback.
     */
    private fun buildSettingsChildren(): List<MediaItem> = listOf(
        buildBrowsableItem(
            id       = SETTINGS_LOCALE_ROOT,
            title    = "🌐 Ngôn ngữ giọng đọc",
            subtitle = "Hiện tại: ${if (player.getLocale() == "vi-VN") "Tiếng Việt" else "English"}"
        ),
        buildBrowsableItem(
            id       = SETTINGS_SPEED_ROOT,
            title    = "⏩ Tốc độ đọc",
            subtitle = "Hiện tại: ${player.getSpeed()}x"
        ),
        buildBrowsableItem(
            id       = SETTINGS_PITCH_ROOT,
            title    = "🎵 Cao độ giọng",
            subtitle = "Hiện tại: ${player.getPitch()}x"
        )
    )

    /** FIX #2: Danh sách option ngôn ngữ */
    private fun buildLocaleOptionItems(): List<MediaItem> {
        val current = player.getLocale()
        return listOf(
            buildPlayableItem(
                id       = "${PREFIX_SETTING_LOCALE}vi-VN",
                title    = "🇻🇳 Tiếng Việt",
                subtitle = if (current == "vi-VN") "✓ Đang dùng" else "Nhấn để chọn"
            ),
            buildPlayableItem(
                id       = "${PREFIX_SETTING_LOCALE}en-US",
                title    = "🇺🇸 English (US)",
                subtitle = if (current == "en-US") "✓ Đang dùng" else "Nhấn để chọn"
            ),
            buildPlayableItem(
                id       = "${PREFIX_SETTING_LOCALE}en-GB",
                title    = "🇬🇧 English (UK)",
                subtitle = if (current == "en-GB") "✓ Đang dùng" else "Nhấn để chọn"
            )
        )
    }

    /** FIX #2: Danh sách option tốc độ */
    private fun buildSpeedOptionItems(): List<MediaItem> {
        val current = player.getSpeed()
        val speeds  = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        return speeds.map { speed ->
            val label = when (speed) {
                0.5f  -> "🐢 0.5x – Rất chậm"
                0.75f -> "🐌 0.75x – Chậm"
                1.0f  -> "▶️ 1.0x – Bình thường"
                1.25f -> "🚶 1.25x – Nhanh nhẹ"
                1.5f  -> "🚴 1.5x – Nhanh"
                1.75f -> "🏃 1.75x – Rất nhanh"
                2.0f  -> "🚀 2.0x – Tối đa"
                else  -> "${speed}x"
            }
            buildPlayableItem(
                id       = "$PREFIX_SETTING_SPEED$speed",
                title    = label,
                subtitle = if (current == speed.fmt()) "✓ Đang dùng" else ""
            )
        }
    }

    /** FIX #2: Danh sách option cao độ */
    private fun buildPitchOptionItems(): List<MediaItem> {
        val current = player.getPitch()
        val pitches = listOf(0.75f, 0.875f, 1.0f, 1.125f, 1.25f, 1.5f)
        return pitches.map { pitch ->
            val label = when (pitch) {
                0.75f  -> "🎸 0.75x – Giọng trầm"
                0.875f -> "🎷 0.875x – Hơi trầm"
                1.0f   -> "🎵 1.0x – Tự nhiên"
                1.125f -> "🎹 1.125x – Hơi cao"
                1.25f  -> "🎺 1.25x – Cao"
                1.5f   -> "🎻 1.5x – Rất cao"
                else   -> "${pitch}x"
            }
            buildPlayableItem(
                id       = "$PREFIX_SETTING_PITCH$pitch",
                title    = label,
                subtitle = if (current == pitch.fmt()) "✓ Đang dùng" else ""
            )
        }
    }

    // Hàm helper để format float cho so sánh khớp với player.getSpeed()/getPitch()
    private fun Float.fmt(): String =
        if (this == kotlin.math.floor(this.toDouble()).toFloat()) "%.0f".format(this)
        else "%.2f".format(this).trimEnd('0')

    // ══════════════════════════════════════════════════════════════════════
    // SESSION_ROOT special items handler
    // ══════════════════════════════════════════════════════════════════════

    private fun handleSessionItem(mediaId: String) {
        when (mediaId) {
            "SESSION_NEW_CHAT" -> handleCustomCommand(SESSION_CMD_NEW_CHAT, Bundle.EMPTY)
            "SESSION_MIC" -> {
                val intent = Intent(this, VoiceService::class.java).apply {
                    this.action = VoiceService.ACTION_START
                    AppState.currentSession?.id?.let { putExtra("session_id", it) }
                }
                startForegroundService(intent)
            }
            "SESSION_INFO", "SESSION_EMPTY" -> { /* info only */ }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════

    /** FIX #4 / Mic button: Lấy content của message đang phát để làm bot_context */
    private fun getCurrentPlayingBotContent(): String? {
        val playingId = currentPlayingMediaId ?: return null
        return when {
            playingId.startsWith(PREFIX_NEWS_DETAIL) -> {
                val aId = playingId.removePrefix(PREFIX_NEWS_DETAIL).toIntOrNull() ?: return null
                val allMsgs = messageManager.messages + messageManager.historyMessages.values.flatten()
                allMsgs.firstOrNull { msg ->
                    val items = msg.extraData?.get("news_items") as? List<*>
                    items?.any { (it as? Map<*,*>)?.get("article_id")
                        ?.let { id -> (id as? Number)?.toInt() } == aId } == true
                }?.let { msg ->
                    val items = msg.extraData?.get("news_items") as? List<*>
                    val found = items?.filterIsInstance<Map<*,*>>()
                        ?.firstOrNull { (it["article_id"] as? Number)?.toInt() == aId }
                    "${found?.get("title") as? String ?: ""}. ${found?.get("description") as? String ?: ""}"
                }
            }
            playingId.startsWith(PREFIX_MSG_DETAIL) -> {
                val mid = playingId.removePrefix(PREFIX_MSG_DETAIL)
                messageManager.messages.firstOrNull { it.id == mid }?.content
                    ?: messageManager.historyMessages.values.flatten().firstOrNull { it.id == mid }?.content
            }
            playingId.startsWith(PREFIX_CHAT_MSG) -> {
                val mid = playingId.removePrefix(PREFIX_CHAT_MSG)
                messageManager.messages.firstOrNull { it.id == mid }?.content
                    ?: messageManager.historyMessages.values.flatten().firstOrNull { it.id == mid }?.content
            }
            else -> null
        }
    }

    private suspend fun synthesizeAndCache(key: String, text: String): android.net.Uri? {
        audioCache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val outFile = File(cacheDir, "msg_${key.take(50)}.wav")
                if (outFile.exists() && outFile.length() > 0) {
                    val uri = FileProvider.getUriForFile(applicationContext, "${packageName}.provider", outFile)
                    audioCache[key] = uri; return@withContext uri
                }
                val success = player.synthesizeToFileSync(text, outFile)
                if (success && outFile.exists() && outFile.length() > 0) {
                    val uri = FileProvider.getUriForFile(applicationContext, "${packageName}.provider", outFile)
                    audioCache[key] = uri; uri
                } else null
            } catch (e: Exception) {
                Log.e("MEDIA_SVC", "synthesizeAndCache [$key]: ${e.message}"); null
            }
        }
    }

    private fun findMessage(mediaId: String) = when {
        mediaId.startsWith(PREFIX_MSG_DETAIL) -> {
            val id = mediaId.removePrefix(PREFIX_MSG_DETAIL)
            messageManager.messages.firstOrNull { it.id == id }
                ?: messageManager.historyMessages.values.flatten().firstOrNull { it.id == id }
        }
        mediaId.startsWith(PREFIX_CHAT_MSG) -> {
            val id = mediaId.removePrefix(PREFIX_CHAT_MSG)
            messageManager.messages.firstOrNull { it.id == id }
                ?: messageManager.historyMessages.values.flatten().firstOrNull { it.id == id }
        }
        else -> null
    }

    private fun buildArticleText(article: com.example.autochat.domain.model.Article) = buildString {
        append(article.title).append(". ")
        if (!article.description.isNullOrBlank()) append(article.description).append(". ")
        if (!article.author.isNullOrBlank())      append("Bài viết của ").append(article.author).append(". ")
        if (!article.content.isNullOrBlank())     append(article.content)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractNewsExtraData(msg: com.example.autochat.domain.model.Message): Map<String, Any?>? {
        val extra = msg.extraData ?: return null
        if (extra["type"] as? String != "news_list") return null
        val items = extra["news_items"] as? List<*> ?: return null
        if (items.isEmpty()) return null
        return extra
    }

    private fun buildMediaItemWithUri(
        mediaId   : String,
        uri       : android.net.Uri,
        title     : String,
        subtitle  : String,
        artworkUri: android.net.Uri? = null
    ) = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(title).setSubtitle(subtitle)
            .setArtworkUri(artworkUri)
            .setIsPlayable(true).setIsBrowsable(false).build())
        .build()

    private fun buildBrowsableItem(id: String, title: String, subtitle: String = "", iconResId: Int? = null): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title).setSubtitle(subtitle)
            .setIsBrowsable(true).setIsPlayable(false)
        if (iconResId != null) meta.setArtworkUri("android.resource://${packageName}/$iconResId".toUri())
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(meta.build()).build()
    }

    private fun buildPlayableItem(id: String, title: String, subtitle: String = "", extras: Bundle = Bundle.EMPTY) =
        MediaItem.Builder().setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title).setSubtitle(subtitle)
                .setIsBrowsable(false).setIsPlayable(true)
                .setExtras(extras).build())
            .build()

    private fun noItems() = MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)

    private fun formatTime(timestamp: Long): String {
        return try {
            val date = java.util.Date(timestamp); val now = java.util.Date()
            val sdf  = if (date.year == now.year && date.month == now.month && date.date == now.date)
                java.text.SimpleDateFormat("HH:mm", java.util.Locale("vi"))
            else java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("vi"))
            sdf.format(date)
        } catch (_: Exception) { "--:--" }
    }
}