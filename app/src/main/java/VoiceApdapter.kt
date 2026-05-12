package com.example.autochat.ui.phone.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.R
import com.example.autochat.tts.TtsVoice

class VoiceAdapter(
    private val voices: List<TtsVoice>,
    private var selectedId: String?,
    private val onSelect: (TtsVoice) -> Unit,
    private val onPreview: (TtsVoice) -> Unit,  // ← thêm callback
) : RecyclerView.Adapter<VoiceAdapter.VH>() {

    inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView     = view.findViewById(R.id.tvVoiceName)
        val ivCheck: ImageView   = view.findViewById(R.id.ivVoiceCheck)
        val btnPreview: ImageButton = view.findViewById(R.id.btnPreview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_voice, parent, false))

    override fun getItemCount() = voices.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val voice = voices[position]
        holder.tvName.text = voice.name
        holder.ivCheck.visibility = if (voice.id == selectedId) View.VISIBLE else View.INVISIBLE

        holder.view.setOnClickListener {
            val old = voices.indexOfFirst { it.id == selectedId }
            selectedId = voice.id
            notifyItemChanged(old)
            notifyItemChanged(position)
            onSelect(voice)
        }

        holder.btnPreview.setOnClickListener {
            onPreview(voice)  // ← delegate lên fragment
        }
    }
}