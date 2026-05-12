// fragment/SettingFragment.kt
package com.example.autochat.ui.phone.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.autochat.AppState
import com.example.autochat.R
import com.example.autochat.databinding.FragmentSettingBinding
import com.example.autochat.ui.phone.AuthActivity
import com.example.autochat.ui.phone.adapter.SettingAdapter
import com.example.autochat.ui.phone.model.SettingAction
import com.example.autochat.ui.phone.model.SettingItem
import com.example.autochat.ui.phone.model.SettingViewType
import com.example.autochat.ui.phone.model.ThemeMode
import com.example.autochat.ui.phone.model.viewType
import com.example.autochat.ui.phone.viewmodel.SettingViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingFragment : Fragment() {

    private var _binding: FragmentSettingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingViewModel by viewModels()
    private lateinit var adapter: SettingAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (requireActivity() as AppCompatActivity).supportActionBar?.hide()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (requireActivity() as AppCompatActivity).supportActionBar?.hide()
        setupRecyclerView()
        observeViewModel()
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = SettingAdapter(
            // NAVIGATE → Fragment transaction
            onNavigate = { action -> navigateTo(action) },

            // EXPAND → ViewModel toggle (hiện chỉ dùng cho theme)
            onExpand   = { item ->
                when (item.id) {
                    "theme" -> viewModel.toggleThemeExpanded()
                }
            },

            // DIALOG → hiện dialog/bottom-sheet tương ứng
            onDialog   = { action -> handleDialog(action.tag) },

            // CUSTOM → xử lý tại Fragment (cần confirm UI, v.v.)
            onCustom   = { action -> handleCustom(action.id) },

            // Theme select (chỉ từ ThemePickerViewHolder)
            onThemeSelect = { mode -> viewModel.setTheme(mode) },
        ).also {
            it.username = AppState.username
            it.email    = "trandat@gmail.com"
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter       = this@SettingFragment.adapter
            itemAnimator  = null  // tránh flicker khi list rebuild
        }
    }

    // ── Action handlers ───────────────────────────────────────────────────────

    /** Mở Fragment mới (push backstack) */
    private fun navigateTo(action: SettingAction.Navigate) {
        val fragment = action.destination.getDeclaredConstructor().newInstance()
        action.args?.let { fragment.arguments = it }
        parentFragmentManager.beginTransaction()
            .replace(R.id.settingFragment, fragment)
            .addToBackStack(null)
            .commit()
    }

    /** Hiện dialog hoặc bottom-sheet theo tag */
    private fun handleDialog(tag: String) {
        when (tag) {
            "generate_pin" -> viewModel.generatePin()   // ViewModel lo network, Fragment lo hiện dialog
            "language"     -> showLanguageDialog()
            "text_size"    -> showTextSizeDialog()
        }
    }

    /** Xử lý Custom action — thường cần confirm trước */
    private fun handleCustom(id: String) {
        when (id) {
            "logout" -> showLogoutConfirmDialog()
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showLogoutConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.logout))
            .setMessage(getString(R.string.logout_confirm_message))
            .setPositiveButton(getString(R.string.logout)) { _, _ -> viewModel.logout() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showLanguageDialog() {
        // TODO: implement language picker dialog
    }

    private fun showTextSizeDialog() {
        // TODO: implement text size picker dialog
    }

    private var pinDialog: AlertDialog? = null
    private var currentPin: String = ""

    private fun showPinDialog(pin: String) {
        currentPin = pin
        pinDialog?.dismiss()
        pinDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.pin_dialog_title))
            .setMessage(formatPin(pin) + "\n\n" + getString(R.string.pin_expires_in, 5, 0))
            .setPositiveButton(getString(R.string.close)) { _, _ ->
                pinDialog = null; currentPin = ""
            }
            .setCancelable(false)
            .show()
    }

    private fun formatPin(pin: String) = pin.toCharArray().joinToString("  ")

    private fun updatePinCountdown(remaining: Long) {
        if (pinDialog == null || currentPin.isEmpty()) return
        val min = remaining / 60
        val sec = remaining % 60
        pinDialog?.setMessage(
            formatPin(currentPin) + "\n\n" + getString(R.string.pin_expires_in, min, sec)
        )
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        // List items
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.items.collectLatest { items ->
                val header = SettingItem(
                    id     = "header",
                    title  = "",
                    action = SettingAction.None,
                )
                adapter.submitList(listOf(header) + items)
            }
        }

        // UI state
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is SettingViewModel.UiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    is SettingViewModel.UiState.PinGenerated -> {
                        binding.progressBar.visibility = View.GONE
                        showPinDialog(state.pin)
                        viewModel.resetState()
                    }
                    is SettingViewModel.UiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
                        viewModel.resetState()
                    }
                    is SettingViewModel.UiState.LoggedOut -> {
                        binding.progressBar.visibility = View.GONE
                        startActivity(
                            Intent(requireContext(), AuthActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                    }
                    else -> binding.progressBar.visibility = View.GONE
                }
            }
        }

        // PIN countdown
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pinCountdown.collectLatest { remaining ->
                when {
                    remaining > 0   -> updatePinCountdown(remaining)
                    remaining == 0L -> {
                        pinDialog?.dismiss()
                        pinDialog  = null
                        currentPin = ""
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        (requireActivity() as AppCompatActivity).supportActionBar?.show()
        super.onDestroyView()
        _binding = null
    }
}