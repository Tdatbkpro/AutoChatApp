package com.example.autochat.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

class NavigationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val query = intent.getStringExtra("nav_query") ?: return

        val mapsIntent = Intent(Intent.ACTION_VIEW).apply {
            data = "google.navigation:q=${Uri.encode(query)}&mode=d".toUri()
            setPackage("com.google.android.apps.maps")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(mapsIntent)
        } catch (e: Exception) {
            // Fallback nếu không có Google Maps
            try {
                val geo = Intent(Intent.ACTION_VIEW).apply {
                    data = "geo:0,0?q=${Uri.encode(query)}".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(geo)
            } catch (e2: Exception) {
                android.util.Log.e("NavigationReceiver", "Cannot open maps: ${e2.message}")
            }
        }
    }
}