// model/SettingItem.kt
package com.example.autochat.ui.phone.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment

/**
 * Mô tả hành vi khi user tap vào một setting item.
 *
 * NAVIGATE  → mở Fragment mới (push backstack)
 * EXPAND    → toggle panel inline bên dưới item (accordion)
 * DIALOG    → mở popup/dialog
 * CUSTOM    → gọi lambda tuỳ ý (logout, copy, share…)
 * NONE      → chỉ hiển thị, không tương tác (INFO)
 */
sealed class SettingAction {

    /** Điều hướng sang Fragment mới */
    data class Navigate(
        val destination: Class<out Fragment>,
        val args: android.os.Bundle? = null,
    ) : SettingAction()

    /** Mở rộng / thu gọn panel inline */
    data class Expand(
        val isExpanded: Boolean = false,
    ) : SettingAction()

    /** Mở dialog / bottom-sheet */
    data class Dialog(
        val tag: String,          // dùng để ViewModel / Fragment nhận biết dialog nào
    ) : SettingAction()

    /** Hành động tuỳ chỉnh — callback được xử lý ở Fragment/ViewModel */
    data class Custom(
        val id: String,           // e.g. "logout", "generate_pin"
    ) : SettingAction()

    /** Chỉ hiển thị, không click */
    object None : SettingAction()
}

// ─────────────────────────────────────────────────────────────────────────────

data class SettingItem(
    val id: String,

    // Hiển thị
    val title: String,
    val subtitle: String? = null,
    val value: String? = null,
    @DrawableRes val iconRes: Int = 0,
    val isDestructive: Boolean = false,

    // Hành vi
    val action: SettingAction = SettingAction.None,

    // Group card (set bởi ViewModel helper, không cần điền tay)
    val isFirstInGroup: Boolean = false,
    val isLastInGroup: Boolean = false,
    val showDivider: Boolean = false,

    // Chỉ dùng khi action là Expand
    val isExpanded: Boolean = false,
    val currentTheme: ThemeMode = ThemeMode.SYSTEM,
)

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Loại VIEW — adapter dùng để chọn ViewHolder.
 * Tách khỏi SettingAction để một action có thể có nhiều cách render
 * (ví dụ EXPAND có thể render theme-picker hoặc font-size-picker).
 */
enum class SettingViewType {
    HEADER,        // avatar + tên user
    SECTION,       // tiêu đề nhóm
    ITEM,          // row thông thường
    THEME_PICKER,  // row có panel chọn Sáng/Tối/Hệ thống
}

// Computed từ item — adapter gọi hàm này
fun SettingItem.viewType(): SettingViewType = when {
    id == "header"                          -> SettingViewType.HEADER
    id.startsWith("section_")              -> SettingViewType.SECTION
    action is SettingAction.Expand
            && id == "theme"                   -> SettingViewType.THEME_PICKER
    else                                   -> SettingViewType.ITEM
}