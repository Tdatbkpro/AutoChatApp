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
    private val onCancel:   (ModelManager.ModelInfo) -> Unit,   // ← Hủy hoàn toàn
    private val onSelect:   (ModelManager.ModelInfo) -> Unit,
    private val onDelete:   (ModelManager.ModelInfo) -> Unit,
    private val onEdit: (ModelManager.ModelInfo) -> Unit
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

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            updateProgressOnly(holder, getItem(position))
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        bindFull(holder, getItem(position))
    }

    private fun bindFull(holder: ViewHolder, state: ModelState) {
        val model = state.info
        holder.binding.apply {
            tvModelName.text = model.name
            tvModelDesc.text = model.description
            tvModelSize.text = formatSize(model.sizeMB)
            tvModelFormat.visibility = View.VISIBLE
            tvModelFormat.text = "GGUF"

            tvCustomBadge.visibility = if (model.id.startsWith("custom_")) View.VISIBLE else View.GONE

            btnDownload.visibility = View.GONE
            layoutDownloaded.visibility = View.GONE
            layoutProgress.visibility = View.GONE
            layoutActive.visibility = View.GONE
            ivDownloadedBadge.visibility = View.GONE

            // ✅ Ẩn tất cả nút custom row trước
            btnEdit.visibility = View.GONE
            layoutCustomActions.visibility = View.GONE

            when {
                state.isDownloading -> {
                    layoutProgress.visibility = View.VISIBLE
                    btnPauseResume.text = "Tạm dừng"
                    btnPauseResume.setOnClickListener { onPause(model) }
                    btnCancel.visibility = View.VISIBLE
                    btnCancel.setOnClickListener { onCancel(model) }
                    updateProgressOnly(holder, state)
                }
                state.isPaused -> {
                    layoutProgress.visibility = View.VISIBLE
                    btnPauseResume.text = "Tiếp tục"
                    btnPauseResume.setOnClickListener { onResume(model) }
                    btnCancel.visibility = View.VISIBLE
                    btnCancel.setOnClickListener { onCancel(model) }
                    updateProgressOnly(holder, state)
                }
                state.info.isDownloaded -> {
                    layoutDownloaded.visibility = View.VISIBLE
                    ivDownloadedBadge.visibility = View.VISIBLE

                    btnEdit.visibility = if (model.id.startsWith("custom_")) View.VISIBLE else View.GONE
                    btnEdit.setOnClickListener { onEdit(model) }

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
                    // ✅ Chưa download - hiện nút Tải về + Sửa/Xóa nếu là custom
                    if (model.id.startsWith("custom_")) {
                        // Custom: Tải về + Sửa + Xóa
                        layoutCustomActions.visibility = View.VISIBLE
                        btnDownloadCustom.visibility = View.VISIBLE
                        btnDownloadCustom.text = "Tải về"
                        btnDownloadCustom.setOnClickListener { onDownload(model) }
                        btnEditCustom.visibility = View.VISIBLE
                        btnEditCustom.setOnClickListener { onEdit(model) }
                        btnDeleteCustom.visibility = View.VISIBLE
                        btnDeleteCustom.setOnClickListener { onDelete(model) }
                    } else {
                        // Built-in: chỉ Tải về
                        btnDownload.visibility = View.VISIBLE
                        btnDownload.setOnClickListener { onDownload(model) }
                    }
                }
            }
        }
    }

    private fun updateProgressOnly(holder: ViewHolder, state: ModelState) {
        holder.binding.apply {
            val pct = (state.progress * 100).toInt()
            progressDownload.progress = pct
            tvProgressPercent.text    = "$pct%"

            val downloadedStr = formatBytes(state.downloadedBytes)
            val totalStr      = formatBytes(state.totalBytes)
            tvDownloadedSize.text = "$downloadedStr / $totalStr"

            if (state.isPaused) {
                tvSpeed.text = "Đã tạm dừng"
                tvEta.text   = "Nhấn Tiếp tục để tải lại"
                tvEta.visibility = View.VISIBLE
                return
            }

            tvSpeed.text = state.speed

            val speedBps = parseSpeedToBps(state.speed)
            if (speedBps > 0 && state.totalBytes > state.downloadedBytes) {
                val remaining  = state.totalBytes - state.downloadedBytes
                val etaSeconds = (remaining / speedBps).toLong()
                tvEta.text       = "Còn ${formatDuration(etaSeconds)}"
                tvEta.visibility = View.VISIBLE
            } else if (state.downloadedBytes > 0) {
                tvEta.text       = "Đang tính..."
                tvEta.visibility = View.VISIBLE
            } else {
                tvEta.text       = "Bắt đầu..."
                tvEta.visibility = View.VISIBLE
            }
        }
    }

    private fun parseSpeedToBps(speed: String): Float {
        return try {
            val clean = speed.replace(",", ".").replace("MB/s", "").replace("KB/s", "").replace("B/s", "").trim()
            val value = clean.toFloat()
            when {
                speed.contains("MB/s") -> value * 1_000_000
                speed.contains("KB/s") -> value * 1_000
                else -> value
            }
        } catch (e: Exception) { 0f }
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes < 60) "${minutes}:${remainingSeconds.toString().padStart(2, '0')} phút"
        else "${minutes / 60}h${minutes % 60}p"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000f)
        bytes >= 1_000_000     -> String.format("%.1f MB", bytes / 1_000_000f)
        bytes >= 1_000         -> String.format("%.1f KB", bytes / 1_000f)
        else -> "$bytes B"
    }

    private fun formatSize(mb: Long) = if (mb >= 1000) String.format("%.1f GB", mb / 1000f) else "$mb MB"

    class DiffCallback : DiffUtil.ItemCallback<ModelState>() {
        override fun areItemsTheSame(a: ModelState, b: ModelState) = a.info.id == b.info.id

        override fun areContentsTheSame(a: ModelState, b: ModelState): Boolean {
            if (a.isDownloading != b.isDownloading) return false
            if (a.isPaused      != b.isPaused)      return false
            if (a.isActive      != b.isActive)      return false
            if (a.info.isDownloaded != b.info.isDownloaded) return false
            return a.progress == b.progress && a.speed == b.speed && a.downloadedBytes == b.downloadedBytes
        }

        override fun getChangePayload(a: ModelState, b: ModelState): Any? {
            return if (a.isDownloading == b.isDownloading &&
                a.isPaused  == b.isPaused &&
                a.isActive  == b.isActive &&
                a.info.isDownloaded == b.info.isDownloaded && a != b
            ) "progress_update" else null
        }
    }
}