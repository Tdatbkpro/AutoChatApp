package com.example.autochat.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.autochat.ui.phone.VoiceActivity

class VoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.e("VOICE_DEBUG", "VoiceReceiver nhận được broadcast: ${intent.action}")

        if (intent.action == "com.example.autochat.START_VOICE") {
            Log.e("VOICE_DEBUG", "Đang mở VoiceActivity...")
            val activityIntent = Intent(context, VoiceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) // ✅ Kéo lên trên
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)        // ✅ Clear stack cũ
            }
            context.startActivity(activityIntent)
        }
    }

}