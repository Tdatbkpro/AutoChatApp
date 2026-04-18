package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.AppState
import com.example.autochat.R

/**
 * Màn hình đọc toàn bộ một tin nhắn dài của bot.
 *
 * Khi xe đỗ: Dùng LongMessageTemplate — text có thể scroll, không giới hạn độ dài.
 * Khi xe chạy: Dùng MessageTemplate — hiển thị rút gọn, an toàn khi lái xe.
 *
 * ActionStrip có 3 trạng thái TTS giống NewsDetailScreen:
 *
 *   IDLE      → [▶ Play] [↩ Reply]
 *   SPEAKING  → [⏸ Pause] [⏹ Stop]
 *   PAUSED    → [▶ Play] [⏹ Stop]
 *
 * Khởi tạo từ MyChatScreen khi nhấn vào bot message thường (không phải news_list)
 * và content dài hơn ngưỡng MIN_LENGTH_FOR_DETAIL.
 */
class MessageDetailScreen(
    carContext: CarContext,
    private val chatScreen: MyChatScreen,
    private val messageContent: String,
    private val messageTime: String = ""
) : Screen(carContext) {

    companion object {
        // Ngưỡng ký tự để push sang detail thay vì đọc TTS trực tiếp
        const val MIN_LENGTH_FOR_DETAIL = 120
    }

    private enum class TtsState { IDLE, SPEAKING, PAUSED }

    private var ttsState = TtsState.IDLE

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                chatScreen.onTtsDone = {
                    ttsState = TtsState.IDLE
                    invalidate()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                // ✅ Khi rời khỏi màn hình → dừng TTS
                if (ttsState != TtsState.IDLE) {
                    chatScreen.stopSpeak()
                    ttsState = TtsState.IDLE
                }
                chatScreen.onTtsDone = null
            }

            override fun onDestroy(owner: LifecycleOwner) {
                // ✅ Khi màn hình bị hủy → dừng TTS
                if (ttsState != TtsState.IDLE) {
                    chatScreen.stopSpeak()
                    ttsState = TtsState.IDLE
                }
                chatScreen.onTtsDone = null
            }
        })
    }

    // ── TTS actions ───────────────────────────────────────────────────────

    private fun onPlay() {
        chatScreen.resumeSpeak(messageContent)
        ttsState = TtsState.SPEAKING
        CarToast.makeText(carContext, "Đang đọc...", CarToast.LENGTH_SHORT).show()
        invalidate()
    }

    private fun onPause() {
        chatScreen.pauseSpeak()
        ttsState = TtsState.PAUSED
        CarToast.makeText(carContext, "Đã tạm dừng", CarToast.LENGTH_SHORT).show()
        invalidate()
    }

    private fun onStop() {
        chatScreen.stopSpeak()
        ttsState = TtsState.IDLE
        CarToast.makeText(carContext, "Đã dừng", CarToast.LENGTH_SHORT).show()
        invalidate()
    }

    private fun onAskDetail() {
        // Dừng TTS nếu đang chạy
        if (ttsState != TtsState.IDLE) {
            chatScreen.stopSpeak()
            ttsState = TtsState.IDLE
        }
        // Mở VoiceSearchScreen với botMessage
        screenManager.push(
            VoiceSearchScreen(
                carContext = carContext,
                chatScreen = chatScreen,
                botMessage = messageContent,
                {
                    CarToast.makeText(carContext, "Đang hỏi chi tiết về message...", CarToast.LENGTH_SHORT).show()
                    screenManager.popToRoot()
                }
            )
        )
    }

    private fun icon(resId: Int) = CarIcon.Builder(
        IconCompat.createWithResource(carContext, resId)
    ).build()

    // ✅ Helper để rút gọn nội dung khi xe đang chạy
    private fun getShortenedContent(): String {
        // Giới hạn khoảng 200-300 ký tự cho MessageTemplate
        val maxLength = 500
        return if (messageContent.length > maxLength) {
            messageContent.take(maxLength).trim() + "..."
        } else {
            messageContent
        }
    }

    // ── Template ──────────────────────────────────────────────────────────

    override fun onGetTemplate(): Template {
        android.util.Log.e("MESSAGE_DETAIL", "🚗 AppState.isDriving = ${AppState.isDriving}")
        android.util.Log.e("MESSAGE_DETAIL", "📝 Content length = ${messageContent.length}")
        val actionStrip = when (ttsState) {
            TtsState.IDLE -> ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setIcon(icon(android.R.drawable.ic_media_play))
                        .setOnClickListener { onPlay() }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setIcon(icon(R.drawable.ic_reply))
                        .setOnClickListener { onAskDetail() }
                        .build()
                )
                .build()

            TtsState.SPEAKING -> ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setIcon(icon(android.R.drawable.ic_media_pause))
                        .setOnClickListener { onPause() }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setIcon(icon(android.R.drawable.ic_media_rew))
                        .setOnClickListener { onStop() }
                        .build()
                )
                .build()

            TtsState.PAUSED -> ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setIcon(icon(android.R.drawable.ic_media_play))
                        .setOnClickListener { onPlay() }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setIcon(icon(android.R.drawable.ic_media_rew))
                        .setOnClickListener { onStop() }
                        .build()
                )
                .build()
        }

        val titlePrefix = when (ttsState) {
            TtsState.IDLE -> "🤖"
            TtsState.SPEAKING -> "🔊"
            TtsState.PAUSED -> "⏸"
        }
        val titleSuffix = if (messageTime.isNotBlank()) " • $messageTime" else ""
        val title = "$titlePrefix Bot$titleSuffix"

        // ✅ Phân nhánh UI dựa trên trạng thái lái xe từ AppState
        return if (AppState.isDriving) {
            // 🚗 Xe đang chạy → MessageTemplate (giới hạn nội dung, an toàn)
            MessageTemplate.Builder(getShortenedContent())
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .setActionStrip(actionStrip)
                .build()
        } else {
            // 🅿️ Xe đỗ → LongMessageTemplate (hiển thị toàn bộ nội dung)
            LongMessageTemplate.Builder(messageContent)
                .setTitle(title)
                .setHeaderAction(Action.BACK)
                .setActionStrip(actionStrip)
                .build()
        }
    }
}