// viewmodel/SettingViewModel.kt
package com.example.autochat.ui.phone.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autochat.AppState
import com.example.autochat.R
import com.example.autochat.domain.repository.AuthRepository

import com.example.autochat.ui.phone.fragment.SettingFragment
import com.example.autochat.ui.phone.model.SettingAction
import com.example.autochat.ui.phone.model.SettingItem
import com.example.autochat.ui.phone.model.ThemeMode
import com.example.autochat.ui.phone.util.ThemePreference
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val themePreference: ThemePreference,
) : ViewModel() {

    // ── UI State ──────────────────────────────────────────────────────────────

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        data class PinGenerated(val pin: String) : UiState()
        data class Error(val message: String) : UiState()
        object LoggedOut : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    private val _pinCountdown = MutableStateFlow<Long>(-1L)
    val pinCountdown: StateFlow<Long> = _pinCountdown

    private val _items = MutableStateFlow<List<SettingItem>>(emptyList())
    val items: StateFlow<List<SettingItem>> = _items

    // ── Internal state ────────────────────────────────────────────────────────

    private var themeExpanded = false
    private var currentTheme  = themePreference.getThemeMode()

    private fun str(res: Int) = context.getString(res)

    init { rebuildItems() }

    // ── List builder ──────────────────────────────────────────────────────────

    private fun rebuildItems() {
        val list = mutableListOf<SettingItem>()

        // ── Hồ sơ ────────────────────────────────────────────────────
        list += section("section_profile", R.string.section_profile)
        list += group(
            SettingItem(
                id      = "personalize",
                title   = str(R.string.setting_personalize),
                iconRes = R.drawable.ic_personalize,
                // Tap → mở PersonalizeFragment
                action  = SettingAction.Navigate(SettingFragment::class.java),
            ),
            SettingItem(
                id      = "memory",
                title   = str(R.string.setting_memory),
                iconRes = R.drawable.ic_database,
                // Tap → mở MemoryFragment
                action  = SettingAction.Navigate(SettingFragment::class.java),
            ),
        )

        // ── Tài khoản ─────────────────────────────────────────────────
        list += section("section_account", R.string.section_account)
        list += group(
            SettingItem(
                id       = "workspace",
                title    = str(R.string.setting_workspace),
                subtitle = str(R.string.setting_workspace_subtitle),
                iconRes  = R.drawable.ic_workspace,
                // Tap → mở WorkspaceFragment
                action   = SettingAction.Navigate(SettingFragment::class.java),
            ),
            SettingItem(
                id       = "email",
                title    = str(R.string.setting_email),
                subtitle = AppState.userEmail.ifEmpty { str(R.string.setting_email_empty) },
                iconRes  = R.drawable.ic_email,
                // Chỉ hiển thị, không click
                action   = SettingAction.None,
            ),
            SettingItem(
                id       = "phone",
                title    = str(R.string.setting_phone),
                subtitle = str(R.string.setting_phone_empty),
                iconRes  = R.drawable.ic_phone,
                // Chỉ hiển thị, không click
                action   = SettingAction.None,
            ),
        )

        // ── Android Auto ──────────────────────────────────────────────
        list += section("section_car", R.string.section_android_auto)
        list += group(
            SettingItem(
                id       = "generate_pin",
                title    = str(R.string.setting_generate_pin),
                subtitle = str(R.string.setting_generate_pin_subtitle),
                iconRes  = R.drawable.ic_pin_code,
                // Tap → mở dialog PIN
                action   = SettingAction.Dialog(tag = "generate_pin"),
            ),
        )

        // ── Ứng dụng ──────────────────────────────────────────────────
        list += section("section_app", R.string.section_app)
        list += group(
            SettingItem(
                id           = "theme",
                title        = str(R.string.setting_theme),
                iconRes      = R.drawable.ic_sun,
                isExpanded   = themeExpanded,
                currentTheme = currentTheme,
                // Tap → expand/collapse inline panel
                action       = SettingAction.Expand(isExpanded = themeExpanded),
            ),
        )
        list += group(
            SettingItem(
                id       = "language",
                title    = str(R.string.setting_language),
                subtitle = str(R.string.setting_language_value),
                iconRes  = R.drawable.ic_language,
                // Tap → mở dialog chọn ngôn ngữ
                action   = SettingAction.Dialog(tag = "language"),
            ),
            SettingItem(
                id       = "text_size",
                title    = str(R.string.setting_text_size),
                subtitle = str(R.string.setting_text_size_value),
                iconRes  = R.drawable.ic_text_size,
                // Tap → mở dialog chọn cỡ chữ
                action   = SettingAction.Dialog(tag = "text_size"),
            ),
        )

        // ── Thông tin & Đăng xuất ─────────────────────────────────────
        list += section("section_danger", title = "")
        list += group(
            SettingItem(
                id       = "app_version",
                title    = str(R.string.setting_app_version),
                subtitle = str(R.string.app_name),
                value    = str(R.string.app_version_value),
                iconRes  = R.drawable.ic_auto,
                // Chỉ hiển thị
                action   = SettingAction.None,
            ),
            SettingItem(
                id            = "logout",
                title         = str(R.string.setting_logout),
                subtitle      = str(R.string.setting_logout_subtitle),
                iconRes       = R.drawable.ic_logout,
                isDestructive = true,
                // Tap → custom action (Fragment xử lý confirm dialog)
                action        = SettingAction.Custom(id = "logout"),
            ),
        )

        _items.value = list
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun section(id: String, titleRes: Int) =
        SettingItem(id = id, title = str(titleRes), action = SettingAction.None)

    private fun section(id: String, title: String) =
        SettingItem(id = id, title = title, action = SettingAction.None)

    private fun group(vararg items: SettingItem): List<SettingItem> {
        if (items.isEmpty()) return emptyList()
        return items.mapIndexed { i, item ->
            item.copy(
                isFirstInGroup = i == 0,
                isLastInGroup  = i == items.lastIndex,
                showDivider    = i < items.lastIndex,
            )
        }
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    fun toggleThemeExpanded() {
        themeExpanded = !themeExpanded
        rebuildItems()
    }

    fun setTheme(mode: ThemeMode) {
        currentTheme  = mode
        themeExpanded = false
        themePreference.setThemeMode(mode)
        rebuildItems()
    }

    // ── PIN ───────────────────────────────────────────────────────────────────

    fun generatePin() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val pin = authRepository.generatePin()
                _uiState.value = UiState.PinGenerated(pin)
                startCountdown()
            } catch (e: Exception) {
                _uiState.value = UiState.Error(when {
                    e.message?.contains("401") == true     -> str(R.string.error_session_expired)
                    e.message?.contains("timeout") == true -> str(R.string.error_no_connection)
                    else                                   -> str(R.string.error_pin_failed)
                })
            }
        }
    }

    private fun startCountdown() {
        viewModelScope.launch {
            for (remaining in 300 downTo 0) {
                _pinCountdown.value = remaining.toLong()
                if (remaining == 0) { _uiState.value = UiState.Idle; break }
                delay(1000)
            }
        }
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    fun logout() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                authRepository.logout()
                _uiState.value = UiState.LoggedOut
            } catch (e: Exception) {
                _uiState.value = UiState.Error(str(R.string.error_logout_failed))
            }
        }
    }

    fun resetState() { _uiState.value = UiState.Idle }
}