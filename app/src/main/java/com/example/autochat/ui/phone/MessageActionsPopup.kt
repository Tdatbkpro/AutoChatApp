package com.example.autochat.ui.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.PopupWindow
import android.widget.Toast
import com.example.autochat.R
import com.example.autochat.databinding.BottomSheetEditMessageBinding
import com.example.autochat.databinding.PopupMessageActionsBinding
import com.example.autochat.domain.model.Message
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hiển thị popup action (Retry / Copy / Edit) tại vị trí nhấn giữ,
 * và bottom sheet chỉnh sửa khi chọn Edit.
 */
object MessageActionsPopup {

    interface Listener {
        fun onRetry(message: Message)
        fun onEdit(message: Message, newContent: String)
    }

    /**
     * @param anchorView  View của message item (dùng để tính tọa độ)
     * @param touchX      rawX từ MotionEvent lúc long press
     * @param touchY      rawY từ MotionEvent lúc long press
     */
    fun show(
        context:    Context,
        anchorView: View,
        touchX:     Float,
        touchY:     Float,
        message:    Message,
        listener:   Listener,
    ) {
        val binding = PopupMessageActionsBinding.inflate(LayoutInflater.from(context))

        val popup = PopupWindow(
            binding.root,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true  // focusable → dismiss on outside touch
        ).apply {
            elevation     = 24f
            animationStyle = 0   // custom animation bên dưới
            isOutsideTouchable = true
        }

        // ── Tính vị trí hiển thị ──────────────────────────────────────────────
        // Đo popup trước khi hiện để biết kích thước
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupW = binding.root.measuredWidth
        val popupH = binding.root.measuredHeight

        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels

        // Căn giữa theo touchX, đẩy lên trên touchY (không che ngón tay)
        val offsetAboveFinger = 16.dpToPx(context)
        var xPos = (touchX - popupW / 2).toInt().coerceIn(8, screenW - popupW - 8)
        var yPos = (touchY - popupH - offsetAboveFinger).toInt()

        // Nếu trên quá màn hình → hiện phía dưới ngón tay
        if (yPos < 0) yPos = (touchY + offsetAboveFinger).toInt()

        // ── Hiển thị tại tọa độ tuyệt đối ───────────────────────────────────
        popup.showAtLocation(anchorView, Gravity.NO_GRAVITY, xPos, yPos)

        // ── Entrance animation (scale từ tâm popup) ──────────────────────────
        binding.root.apply {
            scaleX = 0.7f; scaleY = 0.7f; alpha = 0f
            animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(2f))
                .start()
        }

        // ── Actions ───────────────────────────────────────────────────────────
        binding.actionRetry.setOnClickListener {
            dismissWithAnim(binding.root, popup)
            listener.onRetry(message)
        }

        binding.actionCopy.setOnClickListener {
            dismissWithAnim(binding.root, popup)
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("message", message.content))
            Toast.makeText(context, "✅ Đã sao chép", Toast.LENGTH_SHORT).show()
        }

        binding.actionEdit.setOnClickListener {
            dismissWithAnim(binding.root, popup)
            showEditBottomSheet(context, message, listener)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Edit bottom sheet
    // ─────────────────────────────────────────────────────────────────────────

    private fun showEditBottomSheet(
        context:  Context,
        message:  Message,
        listener: Listener,
    ) {
        val dialog = BottomSheetDialog(context, R.style.BottomSheetRunCodeTheme)
        val binding = BottomSheetEditMessageBinding.inflate(LayoutInflater.from(context))

        // Hiện nội dung gốc
        binding.tvOriginalMessage.text = message.content

        // Pre-fill nội dung mới = nội dung gốc (để user chỉnh)
        binding.etEditContent.setText(message.content)
        binding.etEditContent.setSelection(message.content.length) // cursor cuối

        // Char counter
        binding.tvCharCount.text = "${message.content.length}"
        binding.etEditContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.tvCharCount.text = "${s?.length ?: 0}"
                val hasChange = s?.toString()?.trim() != message.content.trim()
                val isEmpty   = s.isNullOrBlank()
                binding.btnSendEdit.alpha   = if (hasChange && !isEmpty) 1f else 0.4f
                binding.btnSendEdit.isEnabled = hasChange && !isEmpty
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Khởi tạo button state
        binding.btnSendEdit.alpha    = 0.4f
        binding.btnSendEdit.isEnabled = false

        // Cancel
        binding.btnCancelEdit.setOnClickListener { dialog.dismiss() }

        // Send
        binding.btnSendEdit.setOnClickListener {
            val newContent = binding.etEditContent.text.toString().trim()
            if (newContent.isBlank() || newContent == message.content.trim()) return@setOnClickListener

            // Show loading
            binding.progressEdit.visibility  = android.view.View.VISIBLE
            binding.tvSendEditLabel.text      = "Đang gửi…"
            binding.btnSendEdit.isEnabled     = false
            binding.btnCancelEdit.isEnabled   = false

            // Delegate → Fragment/ViewModel xử lý
            listener.onEdit(message, newContent)
            dialog.dismiss()
        }

        dialog.setContentView(binding.root)
        dialog.behavior.apply {
            state         = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable   = true
        }

        // Focus vào EditText sau khi dialog mở
        dialog.setOnShowListener {
            binding.etEditContent.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            binding.etEditContent.postDelayed({
                imm.showSoftInput(binding.etEditContent, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }

        dialog.show()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun dismissWithAnim(root: View, popup: PopupWindow) {
        root.animate()
            .scaleX(0.8f).scaleY(0.8f).alpha(0f)
            .setDuration(150)
            .withEndAction { popup.dismiss() }
            .start()
    }

    private fun Int.dpToPx(context: Context): Int =
        (this * context.resources.displayMetrics.density).toInt()
}