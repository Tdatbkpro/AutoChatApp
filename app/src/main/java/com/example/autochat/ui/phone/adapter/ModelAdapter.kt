package com.example.autochat.ui.phone.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.databinding.ItemModelInfoBinding
import com.example.autochat.llm.ModelManager

class ModelAdapter(
    private val onDownload: (ModelManager.ModelInfo) -> Unit,
    private val onPause:    (ModelManager.ModelInfo) -> Unit,
    private val onResume:   (ModelManager.ModelInfo) -> Unit,
    private val onSelect:   (ModelManager.ModelInfo) -> Unit,
    private val onDelete:   (ModelManager.ModelInfo) -> Unit
) : ListAdapter<ModelAdapter.ModelState, ModelAdapter.ViewHolder>(DiffCallback()) {

    data class ModelState(
        val info: ModelManager.ModelInfo,
        val isDownloading: Boolean,
        val isPaused: Boolean,
        val progress: Float,
        val speed: String,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val isActive: Boolean = false
    )

    class ViewHolder(val binding: ItemModelInfoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemModelInfoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    // ✅ Dùng payload để chỉ update progress, không rebuild cả item
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            // Chỉ update progress
            val state = getItem(position)
            updateProgressOnly(holder, state)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val state = getItem(position)
        bindFull(holder, state)
    }

    // ✅ Update toàn bộ item (khi trạng thái thay đổi)
    private fun bindFull(holder: ViewHolder, state: ModelState) {
        val model = state.info
        holder.binding.apply {
            tvModelName.text = model.name
            tvModelDesc.text = model.description
            tvModelSize.text = formatSize(model.sizeMB)

            tvModelFormat.visibility = View.VISIBLE
            tvModelFormat.text = if (model.name.contains("Q4", true)) "Q4" else "GGUF"

            // Reset visibility
            btnDownload.visibility = View.GONE
            layoutDownloaded.visibility = View.GONE
            layoutProgress.visibility = View.GONE
            layoutActive.visibility = View.GONE
            ivDownloadedBadge.visibility = View.GONE

            when {
                state.isDownloading -> {
                    layoutProgress.visibility = View.VISIBLE
                    btnPauseResume.text = "Tạm dừng"
                    btnPauseResume.icon = holder.itemView.context.getDrawable(com.example.autochat.R.drawable.ic_pause)
                    btnPauseResume.setOnClickListener { onPause(model) }
                    updateProgressOnly(holder, state)  // ✅ Gọi update progress
                }

                state.isPaused -> {
                    layoutProgress.visibility = View.VISIBLE
                    btnPauseResume.text = "Tiếp tục"
                    btnPauseResume.icon = holder.itemView.context.getDrawable(com.example.autochat.R.drawable.ic_play)
                    btnPauseResume.setOnClickListener { onResume(model) }
                    updateProgressOnly(holder, state)  // ✅ Gọi update progress
                }

                model.isDownloaded -> {
                    layoutDownloaded.visibility = View.VISIBLE
                    ivDownloadedBadge.visibility = View.VISIBLE

                    if (state.isActive) {
                        layoutActive.visibility = View.VISIBLE
                        btnSelect.text = "Đang sử dụng"
                        btnSelect.alpha = 0.7f
                        btnSelect.isEnabled = false
                    } else {
                        btnSelect.text = "Sử dụng model"
                        btnSelect.alpha = 1f
                        btnSelect.isEnabled = true
                    }

                    btnSelect.setOnClickListener { onSelect(model) }
                    btnDelete.setOnClickListener { onDelete(model) }
                }

                else -> {
                    btnDownload.visibility = View.VISIBLE
                    btnDownload.setOnClickListener { onDownload(model) }
                }
            }
        }
    }

    private fun updateProgressOnly(holder: ViewHolder, state: ModelState) {
        holder.binding.apply {
            val pct = (state.progress * 100).toInt()
            progressDownload.progress = pct
            tvProgressPercent.text = "$pct%"

            // ✅ Dùng downloadedBytes thực tế
            val downloadedStr = formatBytes(state.downloadedBytes)
            val totalStr = formatBytes(state.totalBytes)
            tvDownloadedSize.text = "$downloadedStr / $totalStr"

            // ✅ Luôn hiển thị speed
            tvSpeed.text = state.speed.ifEmpty { "0 B/s" }

            // ✅ Tính ETA
            val speedBps = parseSpeedToBps(state.speed)
            if (speedBps > 0 && state.downloadedBytes > 0 && state.totalBytes > state.downloadedBytes) {
                val remainingBytes = state.totalBytes - state.downloadedBytes
                val etaSeconds = (remainingBytes / speedBps).toLong()
                tvEta.text = "Còn ${formatDuration(etaSeconds)}"
                tvEta.visibility = View.VISIBLE
            } else if (state.downloadedBytes > 0) {
                tvEta.text = "Đang tính..."
                tvEta.visibility = View.VISIBLE
            } else {
                tvEta.text = "Bắt đầu..."
                tvEta.visibility = View.VISIBLE
            }
            android.util.Log.d("ModelAdapter",
                "id=${state.info.id} progress=${state.progress} " +
                        "downloaded=${state.downloadedBytes} total=${state.totalBytes} " +
                        "speed=${state.speed}")
        }
    }

    private fun parseSpeedToBps(speed: String): Float {
        return try {
            // ✅ Thay dấu phẩy bằng dấu chấm, xóa "MB/s", "KB/s", "B/s"
            val cleanSpeed = speed
                .replace(",", ".")       // 19,2 → 19.2
                .replace("MB/s", "")     // bỏ MB/s
                .replace("KB/s", "")     // bỏ KB/s
                .replace("B/s", "")      // bỏ B/s
                .trim()

            val value = cleanSpeed.toFloat()
            when {
                speed.contains("MB/s") -> value * 1_000_000
                speed.contains("KB/s") -> value * 1_000
                else -> value
            }
        } catch (e: Exception) {
            0f
        }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes < 60) {
            "${minutes}:${remainingSeconds.toString().padStart(2, '0')} phút"
        } else {
            val hours = minutes / 60
            val remainingMinutes = minutes % 60
            "${hours}h${remainingMinutes}p"
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000f)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000f)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000f)
            else -> "$bytes B"
        }
    }

    private fun formatSize(mb: Long) = when {
        mb >= 1000 -> String.format("%.1f GB", mb / 1000f)
        else -> "$mb MB"
    }

    // ✅ DiffCallback với payload để không rebuild khi chỉ progress thay đổi
    class DiffCallback : DiffUtil.ItemCallback<ModelState>() {
        override fun areItemsTheSame(a: ModelState, b: ModelState) = a.info.id == b.info.id

        // ✅ LUÔN TRẢ VỀ FALSE KHI ĐANG DOWNLOAD ĐỂ FORCE UPDATE
        override fun areContentsTheSame(a: ModelState, b: ModelState): Boolean {
            // Nếu đang download hoặc paused, luôn return false để update UI
            if (a.isDownloading || b.isDownloading || a.isPaused || b.isPaused) {
                return false  // ← LUÔN UPDATE KHI ĐANG TẢI
            }
            return a == b
        }

        override fun getChangePayload(a: ModelState, b: ModelState): Any? = null
    }
}