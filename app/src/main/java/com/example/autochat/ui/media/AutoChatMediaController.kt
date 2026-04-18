package com.example.autochat.ui.media

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * AutoChatMediaController
 *
 * Convenience wrapper used by the Android Auto / phone UI to send
 * custom commands to [AutoChatMediaService].
 *
 * All command methods mirror the actions previously triggered by
 * clicking buttons / rows in the Car UI screens:
 *
 *  MyChatScreen "🎤 Nói"          → startVoiceInput()
 *  MyChatScreen send text          → sendMessage()
 *  MyChatScreen "navigate"         → requestNavigation()
 *  HistoryScreen "new chat"        → startNewChat()
 *  HistoryDetailScreen load        → loadSession()
 *  HistoryDetailScreen delete      → deleteSession()
 *  HistoryDetailScreen rename      → renameSession()
 *  TtsSettingsScreen save speed    → saveTtsSpeed()
 *  TtsSettingsScreen save pitch    → saveTtsPitch()
 *  TtsSettingsScreen save locale   → saveTtsLocale()
 *  MyChatScreen deleteMessage      → deleteMessagePair()
 *  MyChatScreen sendQuickReply     → sendQuickReply()
 */
@OptIn(UnstableApi::class)
class AutoChatMediaController private constructor(
    private val controller: MediaController
) {

    companion object {
        /**
         * Build the controller asynchronously.
         * Call this from an Activity / Fragment onCreate.
         *
         * ```kotlin
         * AutoChatMediaController.buildAsync(this) { ctrl ->
         *     // use ctrl
         * }
         * ```
         */
        fun buildAsync(context: Context, onReady: (AutoChatMediaController) -> Unit) {
            val token = SessionToken(
                context,
                ComponentName(context, AutoChatMediaService::class.java)
            )
            val future: ListenableFuture<MediaController> =
                MediaController.Builder(context, token).buildAsync()

            future.addListener({
                try {
                    onReady(AutoChatMediaController(future.get()))
                } catch (e: Exception) {
                    Log.e("MEDIA_CTRL", "buildAsync failed: ${e.message}")
                }
            }, MoreExecutors.directExecutor())
        }
    }

    // ── TTS ────────────────────────────────────────────────────────────────

    /** Mirror of MyChatScreen "🎤 Nói" action strip button */
    fun startVoiceInput() = sendCmd(AutoChatMediaService.SESSION_CMD_VOICE_INPUT)

    /** Mirror of MyChatScreen.stopSpeak */
    fun stopTts() = sendCmd(AutoChatMediaService.SESSION_CMD_STOP_TTS)

    // ── Messaging ──────────────────────────────────────────────────────────

    /**
     * Mirror of MyChatScreen.addUserMessage.
     * @param text       the user's message
     * @param botMessage optional context message (for follow-up mode)
     */
    fun sendMessage(text: String, botMessage: String? = null) {
        val args = Bundle().apply {
            putString(AutoChatMediaService.KEY_MESSAGE_TEXT, text)
            if (botMessage != null) putString(AutoChatMediaService.KEY_BOT_MESSAGE, botMessage)
        }
        sendCmd(AutoChatMediaService.SESSION_CMD_SEND_MESSAGE, args)
    }

    // ── Quick replies ──────────────────────────────────────────────────────

    /**
     * Mirror of MyChatScreen.sendQuickQuery.
     * @param articleId  the article ID (or -1 for non-article items)
     * @param category   article category for slot refresh
     * @param slotIndex  grid slot index for refresh
     */
    fun sendQuickReply(articleId: Int, category: String?, slotIndex: Int) {
        val args = Bundle().apply {
            putInt(AutoChatMediaService.KEY_ARTICLE_ID, articleId)
            putString(AutoChatMediaService.KEY_ARTICLE_CATEGORY, category)
            putInt(AutoChatMediaService.KEY_QR_SLOT_INDEX, slotIndex)
        }
        sendCmd(AutoChatMediaService.SESSION_CMD_SEND_QUICK_REPLY, args)
    }

    // ── Navigation (mirrors MyChatScreen.navigateTo) ───────────────────────

    fun requestNavigation(navQuery: String, displayName: String) {
        val args = Bundle().apply {
            putString(AutoChatMediaService.KEY_NAV_QUERY, navQuery)
            putString(AutoChatMediaService.KEY_NAV_DISPLAY_NAME, displayName)
        }
        sendCmd(AutoChatMediaService.SESSION_CMD_NAVIGATE, args)
    }

    // ── Session management (mirrors HistoryScreen / HistoryDetailScreen) ───

    /** Mirror of HistoryScreen "new chat" button */
    fun startNewChat() = sendCmd(AutoChatMediaService.SESSION_CMD_NEW_CHAT)

    /** Mirror of HistoryDetailScreen "load session" action */
    fun loadSession(sessionId: String) {
        val args = Bundle().apply { putString(AutoChatMediaService.KEY_SESSION_ID, sessionId) }
        sendCmd(AutoChatMediaService.SESSION_CMD_LOAD_SESSION, args)
    }

    /** Mirror of HistoryDetailScreen.deleteSession */
    fun deleteSession(sessionId: String) {
        val args = Bundle().apply { putString(AutoChatMediaService.KEY_SESSION_ID, sessionId) }
        sendCmd(AutoChatMediaService.SESSION_CMD_DELETE_SESSION, args)
    }

    /** Mirror of HistoryDetailScreen.renameSession */
    fun renameSession(sessionId: String, newTitle: String) {
        val args = Bundle().apply {
            putString(AutoChatMediaService.KEY_SESSION_ID, sessionId)
            putString(AutoChatMediaService.KEY_NEW_TITLE, newTitle)
        }
        sendCmd(AutoChatMediaService.SESSION_CMD_RENAME_SESSION, args)
    }

    // ── Message deletion (mirrors MyChatScreen.deleteUserMessage) ─────────

    fun deleteMessagePair(sessionId: String, userMsgId: String, botMsgId: String?) {
        val args = Bundle().apply {
            putString(AutoChatMediaService.KEY_SESSION_ID, sessionId)
            putString(AutoChatMediaService.KEY_USER_MSG_ID, userMsgId)
            if (botMsgId != null) putString(AutoChatMediaService.KEY_BOT_MSG_ID, botMsgId)
        }
        sendCmd(AutoChatMediaService.SESSION_CMD_DELETE_MSG_PAIR, args)
    }

    // ── TTS settings (mirrors TtsSettingsScreen) ───────────────────────────

    fun saveTtsSpeed(speed: Float) {
        val args = Bundle().apply { putFloat(AutoChatMediaService.KEY_TTS_SPEED, speed) }
        sendCmd(AutoChatMediaService.SESSION_CMD_TTS_SETTINGS, args)
    }

    fun saveTtsPitch(pitch: Float) {
        val args = Bundle().apply { putFloat(AutoChatMediaService.KEY_TTS_PITCH, pitch) }
        sendCmd(AutoChatMediaService.SESSION_CMD_TTS_SETTINGS, args)
    }

    fun saveTtsLocale(locale: String) {
        val args = Bundle().apply { putString(AutoChatMediaService.KEY_TTS_LOCALE, locale) }
        sendCmd(AutoChatMediaService.SESSION_CMD_TTS_SETTINGS, args)
    }

    // ── Standard playback passthrough ──────────────────────────────────────

    val isPlaying: Boolean get() = controller.isPlaying
    val playbackState: Int  get() = controller.playbackState



    // ── Private ────────────────────────────────────────────────────────────

    private fun sendCmd(action: String, args: Bundle = Bundle.EMPTY) {
        controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }
}