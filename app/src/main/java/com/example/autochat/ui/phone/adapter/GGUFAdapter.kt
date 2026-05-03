package com.example.autochat.ui.phone.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.R
import com.example.autochat.llm.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

class GGUFAdapter(
    private val items: List<ModelManager.GGUFInfo>,
    private val onSelect: (ModelManager.GGUFInfo) -> Unit
) : RecyclerView.Adapter<GGUFAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvGgufName)
        val tvSize: TextView = view.findViewById(R.id.tvGgufSize)
        val tvQuant: TextView = view.findViewById(R.id.tvGgufQuant)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gguf_info, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvName.text = item.filename
        holder.tvQuant.text = item.quantization
        holder.tvSize.text = if (item.sizeMB > 0) "${item.sizeMB}MB" else "?"
        holder.itemView.setOnClickListener { onSelect(item) }
    }

    override fun getItemCount() = items.size
}