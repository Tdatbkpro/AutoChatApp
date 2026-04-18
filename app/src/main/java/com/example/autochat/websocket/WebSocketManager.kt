package com.example.autochat.websocket

import android.util.Log
import com.example.autochat.AppState
import com.example.autochat.domain.model.Message
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSocketManager @Inject constructor() {

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private val listeners = mutableListOf<WebSocketListener>()
    private var currentUserId: String = ""
    private var currentDeviceId: String = ""
    private var connected = false
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    fun isConnected() = connected

    interface WebSocketListener {
        fun onNewMessage(message: Message)
        fun onSessionDeleted(sessionId: String)
        fun onMessagesDeleted(sessionId: String, messageIds: List<String>)
        fun onTyping(userId: String, isTyping: Boolean)
        fun onConnected()
        fun onDisconnected()
        fun onPong()
        fun onJoined(sessionId: String)
        fun onError(error: String)
    }

    fun addListener(listener: WebSocketListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: WebSocketListener) {
        listeners.remove(listener)
    }

    fun setCurrentSession(sessionId: String) {
        AppState.currentSessionId = sessionId
        if (connected) {
            joinSession(sessionId)
        }
    }

    fun connect(userId: String) {
        if (webSocket != null) {
            webSocket?.close(1000, "New connection")
            webSocket = null
        }

        // Cancel reconnect if any
        reconnectJob?.cancel()

        currentUserId = userId
        currentDeviceId = UUID.randomUUID().toString().substring(0, 8)
        val url = "ws://1932wp3f-8000.asse.devtunnels.ms/ws/$userId/$currentDeviceId"

        Log.d("WebSocketManager", "🔌 Connecting to $url")

        client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val request = Request.Builder().url(url).build()

        webSocket = client?.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                Log.d("WebSocketManager", "✅ WebSocket OPENED! DeviceId: $currentDeviceId")
                connected = true
                reconnectAttempts = 0

                // Notify listeners
                listeners.forEach { it.onConnected() }

                // Join current session if exists
                AppState.currentSessionId?.let {
                    joinSession(it)
                }

                // Start ping job
                startPingJob(webSocket)
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.getString("type")

                    Log.d("WebSocketManager", "📨 Received: $type")

                    when (type) {
                        "pong" -> {
                            listeners.forEach { it.onPong() }
                        }

                        "connected" -> {
                            // Welcome message
                            Log.d("WebSocketManager", "Connected to server")
                        }

                        "joined" -> {
                            val sessionId = json.getString("session_id")
                            Log.d("WebSocketManager", "✅ Joined session: $sessionId")
                            listeners.forEach { it.onJoined(sessionId) }
                        }

                        "new_message" -> {
                            val msgObj = json.getJSONObject("message")
                            val message = Message(
                                id = msgObj.getString("id"),
                                sessionId = msgObj.getString("session_id"),
                                content = msgObj.getString("content"),
                                sender = msgObj.getString("sender"),
                                timestamp = parseTimestamp(msgObj.getString("created_at"))
                            )
                            Log.d("WebSocketManager", "📩 New message from ${message.sender}")
                            listeners.forEach { it.onNewMessage(message) }
                        }

                        "typing" -> {
                            val userId = json.getString("user_id")
                            val isTyping = json.getBoolean("is_typing")
                            // Don't trigger typing for self
                            if (userId != currentUserId) {
                                listeners.forEach { it.onTyping(userId, isTyping) }
                            }
                        }

                        "session_deleted" -> {
                            val sessionId = json.getString("session_id")
                            Log.d("WebSocketManager", "🗑️ Session deleted: $sessionId")
                            listeners.forEach { it.onSessionDeleted(sessionId) }
                        }

                        "messages_deleted" -> {
                            val sessionId = json.getString("session_id")
                            val messageIds = json.getJSONArray("message_ids").let { array ->
                                (0 until array.length()).map { array.getString(it) }
                            }
                            Log.d("WebSocketManager", "🗑️ Messages deleted: ${messageIds.size}")
                            listeners.forEach { it.onMessagesDeleted(sessionId, messageIds) }
                        }

                        "error" -> {
                            val error = json.getString("error")
                            Log.e("WebSocketManager", "❌ Server error: $error")
                            listeners.forEach { it.onError(error) }
                        }

                        else -> {
                            Log.d("WebSocketManager", "Unknown message type: $type")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Parse error: ${e.message}", e)
                }
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                Log.d("WebSocketManager", "🔌 Closed: $reason (code: $code)")
                cleanup()
                listeners.forEach { it.onDisconnected() }

                // Attempt reconnect if not normal closure
                if (code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocketManager", "❌ Error: ${t.message}", t)
                cleanup()
                listeners.forEach { it.onDisconnected() }
                scheduleReconnect()
            }

            private fun cleanup() {
                connected = false
                pingJob?.cancel()
                pingJob = null
                this@WebSocketManager.webSocket = null
            }
        })
    }

    private fun startPingJob(webSocket: okhttp3.WebSocket) {
        pingJob?.cancel()
        pingJob = CoroutineScope(Dispatchers.IO).launch {
            while (connected && isActive) {
                delay(25000) // Send ping every 25 seconds
                try {
                    val pingMsg = JSONObject().apply {
                        put("type", "ping")
                    }.toString()
                    webSocket.send(pingMsg)
                    Log.d("WebSocketManager", "📡 Ping sent")
                } catch (e: Exception) {
                    Log.e("WebSocketManager", "Ping failed: ${e.message}")
                    break
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            Log.e("WebSocketManager", "Max reconnect attempts reached")
            return
        }

        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            val delayMs = when (reconnectAttempts) {
                0 -> 1000
                1 -> 2000
                2 -> 5000
                else -> 10000
            }

            delay(delayMs.toLong())
            reconnectAttempts++

            Log.d("WebSocketManager", "🔄 Reconnecting attempt $reconnectAttempts/$maxReconnectAttempts")
            if (currentUserId.isNotEmpty()) {
                connect(currentUserId)
            }
        }
    }

    fun joinSession(sessionId: String) {
        Log.d("WebSocketManager", "joinSession called: sessionId=$sessionId, connected=$connected, currentSessionId=${AppState.currentSessionId}")

        if (!connected) {
            Log.w("WebSocketManager", "Cannot join session: not connected, saving for later")
            AppState.currentSessionId = sessionId
            return
        }

        // ✅ NẾU ĐÃ Ở SESSION NÀY RỒI, KHÔNG GỬI LẠI
        if (AppState.currentSessionId == sessionId) {
            Log.d("WebSocketManager", "Already in session: $sessionId, skip join")
            return
        }

        val json = JSONObject().apply {
            put("type", "join_session")
            put("session_id", sessionId)
        }
        val message = json.toString()
        Log.d("WebSocketManager", "Sending join_session: $message")

        val sent = webSocket?.send(message)
        Log.d("WebSocketManager", "joinSession sent: $sent")

        if (sent == true) {
            AppState.currentSessionId = sessionId
        }
    }

    fun leaveSession(sessionId: String) {
        Log.d("WebSocketManager", "leaveSession called: sessionId=$sessionId, connected=$connected")

        if (!connected) {
            Log.w("WebSocketManager", "Cannot leave session: not connected")
            return
        }

        val json = JSONObject().apply {
            put("type", "leave_session")
            put("session_id", sessionId)
        }
        val message = json.toString()
        Log.d("WebSocketManager", "Sending leave_session: $message")

        val sent = webSocket?.send(message)
        Log.d("WebSocketManager", "leaveSession sent: $sent")

        if (sent == true && sessionId == AppState.currentSessionId) {
            AppState.currentSessionId = null
        }
    }

    fun sendTyping(isTyping: Boolean) {
        if (!connected) return

        val json = JSONObject().apply {
            put("type", "typing")
            put("is_typing", isTyping)
        }
        webSocket?.send(json.toString())
    }

    fun sendReadReceipt(messageIds: List<String>) {
        if (!connected) return

        val json = JSONObject().apply {
            put("type", "read")
            put("message_ids", messageIds)
        }
        webSocket?.send(json.toString())
    }

    fun sendCustomMessage(data: Map<String, Any>) {
        if (!connected) return

        val json = JSONObject().apply {
            put("type", "custom")
            put("data", JSONObject(data))
        }
        webSocket?.send(json.toString())
    }

    fun disconnect() {
        reconnectJob?.cancel()
        pingJob?.cancel()
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        connected = false
        AppState.currentSessionId = null
    }

    private fun parseTimestamp(timestampStr: String): Long {
        return try {
            val cleanStr = timestampStr.split(".")[0]
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            format.parse(cleanStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}