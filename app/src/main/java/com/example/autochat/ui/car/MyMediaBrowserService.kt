package com.example.autochat.ui.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.example.autochat.AppState
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.remote.dto.response.QuickReplyItem
import com.example.autochat.service.VoiceService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MyMediaBrowserService : MediaBrowserServiceCompat() {

    companion object {
        const val ROOT_ID         = "root"
        const val ITEM_MIC        = "action_mic"       // Nút bấm để nói
        const val ITEM_PREFIX_QR  = "qr_"              // quick reply items
        const val ITEM_SETTINGS   = "settings"
    }

    private lateinit var mediaSession: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var quickReplies: List<QuickReplyItem> = emptyList()
    private var lastVoiceResult = ""
    private var lastVoiceTime   = 0L

    private lateinit var voiceResultReceiver: BroadcastReceiver
    private lateinit var voiceStatusReceiver: BroadcastReceiver

    // ── TTS helper (dùng lại AppState.chatScreen nếu còn sống,
    //    hoặc tạo TtsHelper riêng) ──────────────────────────────
    private val ttsHelper by lazy {
        MediaTtsHelper(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        initMediaSession()
        restoreUser()
        registerVoiceReceivers()
        loadQuickReplies()
    }

    // ── MediaSession ──────────────────────────────────────────────────────

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "AIChatbot").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_STOPPED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                                PlaybackStateCompat.ACTION_PAUSE or
                                PlaybackStateCompat.ACTION_STOP
                    )
                    .build()
            )
            setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "AI Chatbot")
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Nhấn mic để hỏi AI")
                    .build()
            )
            setCallback(object : MediaSessionCompat.Callback() {
                // Nút Play vật lý trên xe → bật mic
                override fun onPlay() {
                    triggerVoice()
                }
                override fun onPause() {
                    ttsHelper.stop()
                }
                override fun onStop() {
                    ttsHelper.stop()
                }
                // onMediaButtonEvent xử lý nút mic trên vô lăng
                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == "START_VOICE") triggerVoice()
                }
            })
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
    }

    // ── User restore ──────────────────────────────────────────────────────

    private fun restoreUser() {
        runBlocking {
            try {
                val authRepo = EntryPointAccessors
                    .fromApplication(applicationContext, ChatEntryPoint::class.java)
                    .authRepository()
                val user = authRepo.getCurrentUserFlow().firstOrNull()
                if (user != null && user.accessToken.isNotEmpty()) {
                    AppState.accessToken    = user.accessToken
                    AppState.refreshToken   = user.refreshToken
                    AppState.currentUserId  = user.id
                    AppState.username       = user.username
                    Log.d("MEDIA_SERVICE", "User restored: ${user.id}")
                } else {

                }
            } catch (e: Exception) {
                Log.e("MEDIA_SERVICE", "restoreUser error: ${e.message}")
            }
        }
    }

    // ── Quick replies ─────────────────────────────────────────────────────

    private fun loadQuickReplies() {
        scope.launch {
            try {
                val chatRepo = EntryPointAccessors
                    .fromApplication(applicationContext, ChatEntryPoint::class.java)
                    .chatRepository()
                val items = chatRepo.getQuickReplies(location = "hà nội")
                if (items.isNotEmpty()) {
                    quickReplies = items
                    // Notify Android Auto cần reload danh sách
                    notifyChildrenChanged(ROOT_ID)
                }
            } catch (e: Exception) {
                Log.w("MEDIA_SERVICE", "loadQuickReplies: ${e.message}")
            }
        }
    }

    // ── BroadcastReceiver: nhận kết quả voice ────────────────────────────

    private fun registerVoiceReceivers() {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            RECEIVER_NOT_EXPORTED else 0

        voiceResultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val text = intent.getStringExtra("voice_text") ?: return
                val now  = System.currentTimeMillis()
                if (text == lastVoiceResult && now - lastVoiceTime < 2000) return
                lastVoiceResult = text; lastVoiceTime = now
                Log.d("MEDIA_SERVICE", "Voice result: $text")
                handler.post { handleVoiceInput(text) }
            }
        }

        voiceStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getStringExtra("status") ?: return
                Log.d("MEDIA_SERVICE", "Voice status: $status")
                if (status == "LISTENING") updateNowPlayingMeta("🎤 Đang nghe...", "Hãy nói câu hỏi")
                else if (status == "TIMEOUT") updateNowPlayingMeta("AI Chatbot", "Nhấn mic để hỏi AI")
            }
        }

        registerReceiver(voiceResultReceiver,
            IntentFilter("com.example.autochat.VOICE_RESULT"), flags)
        registerReceiver(voiceStatusReceiver,
            IntentFilter("com.example.autochat.VOICE_STATUS"), flags)
    }

    // ── Voice trigger ─────────────────────────────────────────────────────

    private fun triggerVoice() {
        updateNowPlayingMeta("🎤 Đang nghe...", "Hãy nói câu hỏi của bạn")
        val intent = Intent(this, VoiceService::class.java).apply {
            action = VoiceService.ACTION_START
        }
        startForegroundService(intent)
    }

    // ── Xử lý khi có voice input → gọi AI → TTS ─────────────────────────

    private fun handleVoiceInput(text: String) {
        updateNowPlayingMeta("⏳ AI đang trả lời...", "\"$text\"")
        scope.launch {
            try {
                val chatRepo = EntryPointAccessors
                    .fromApplication(applicationContext, ChatEntryPoint::class.java)
                    .chatRepository()
                val result = chatRepo.sendMessage(
                    sessionId  = AppState.currentSession?.id,
                    content    = text,
                    isFollowUp = false,
                    null
                )
                val botText = result.botMessage.content
                updateNowPlayingMeta("🤖 AI Chatbot", botText.take(60))
                ttsHelper.speak(botText)
            } catch (e: Exception) {
                Log.e("MEDIA_SERVICE", "handleVoiceInput error: ${e.message}")
                updateNowPlayingMeta("AI Chatbot", "Lỗi kết nối, thử lại sau")
                ttsHelper.speak("Xin lỗi, có lỗi kết nối. Vui lòng thử lại.")
            }
        }
    }

    // ── Cập nhật metadata hiển thị trên màn xe ───────────────────────────

    private fun updateNowPlayingMeta(title: String, subtitle: String) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .build()
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_STOP
                )
                .build()
        )
    }

    // ── MediaBrowserServiceCompat ─────────────────────────────────────────

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        Log.d("MEDIA_SERVICE", "onGetRoot: $clientPackageName")
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach() // async
        scope.launch {
            val items = mutableListOf<MediaBrowserCompat.MediaItem>()

            // Item 1: Nút mic — nhấn để nói
            items.add(MediaBrowserCompat.MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId(ITEM_MIC)
                    .setTitle("🎤 Nói chuyện với AI")
                    .setSubtitle("Nhấn để hỏi bất kỳ điều gì")
                    .build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            ))

            // Item 2–7: Quick replies
            val fallback = listOf(
                "💰 Giá vàng hôm nay" to "gold",
                "⛽ Giá xăng hôm nay" to "fuel",
                "🌤️ Thời tiết hôm nay" to "weather",
                "⚽ Tin thể thao" to "the-thao",
                "💹 Tin kinh tế" to "kinh-doanh",
                "📰 Tin thời sự" to "thoi-su",
            )

            val displayItems = quickReplies.takeIf { it.isNotEmpty() }
                ?.take(6)
                ?.mapIndexed { i, qr ->
                    MediaBrowserCompat.MediaItem(
                        MediaDescriptionCompat.Builder()
                            .setMediaId("$ITEM_PREFIX_QR${qr.id ?: qr.category}")
                            .setTitle("${qr.icon} ${qr.category}")
                            .setSubtitle(qr.label.take(60))
                            .build(),
                        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                }
                ?: fallback.map { (label, cat) ->
                    MediaBrowserCompat.MediaItem(
                        MediaDescriptionCompat.Builder()
                            .setMediaId("$ITEM_PREFIX_QR$cat")
                            .setTitle(label)
                            .build(),
                        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                }

            items.addAll(displayItems)
            result.sendResult(items)
        }
    }

    // ── Khi user nhấn item trong danh sách ───────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mediaId = intent?.getStringExtra("media_id")
        if (mediaId != null) handleMediaItemClick(mediaId)
        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleMediaItemClick(mediaId: String) {
        when {
            mediaId == ITEM_MIC -> triggerVoice()
            mediaId.startsWith(ITEM_PREFIX_QR) -> {
                val catOrId = mediaId.removePrefix(ITEM_PREFIX_QR)
                val query = when (catOrId) {
                    "gold"       -> "giá vàng hôm nay"
                    "fuel"       -> "giá xăng dầu hôm nay"
                    "weather"    -> "thời tiết hôm nay"
                    "the-thao"   -> "tin thể thao hôm nay"
                    "kinh-doanh" -> "tin kinh tế hôm nay"
                    "thoi-su"    -> "tin thời sự hôm nay"
                    else -> quickReplies.find {
                        it.id?.toString() == catOrId
                    }?.label ?: "tin tức hôm nay"
                }
                handleVoiceInput(query)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        ttsHelper.shutdown()
        mediaSession.release()
        try {
            unregisterReceiver(voiceResultReceiver)
            unregisterReceiver(voiceStatusReceiver)
        } catch (e: Exception) { }
        super.onDestroy()
    }
}