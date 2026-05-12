package com.example.autochat.ui.phone.fragment

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.autochat.R
import com.example.autochat.databinding.FragmentOtpBinding
import com.example.autochat.ui.phone.AuthActivity
import com.example.autochat.ui.phone.viewmodel.OtpViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class OtpFragment : Fragment(R.layout.fragment_otp) {
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var clipboardManager: ClipboardManager? = null
    private var _binding: FragmentOtpBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OtpViewModel by viewModels()
    private val args: OtpFragmentArgs by navArgs()
    private var isVerifying = false
    private val otpBoxes: List<EditText> by lazy {
        listOf(binding.otp1, binding.otp2, binding.otp3,
            binding.otp4, binding.otp5, binding.otp6)
    }

    private var countdownJob: Job? = null
    private val OTP_TTL_SECONDS = 180

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentOtpBinding.bind(view)

        binding.tvSubtitle.text = "Mã xác thực đã gửi đến\n${args.email}"

        setupOtpBoxes()
        setupButtons()
        observeUiState()
        startCountdown(OTP_TTL_SECONDS)

        requireActivity().window.decorView.viewTreeObserver
            .addOnWindowFocusChangeListener { hasFocus ->
                if (hasFocus && _binding != null) {
                    tryShowPasteHint()
                }
            }
    }
    override fun onResume() {
        super.onResume()
        checkClipboard()  // Android 10+ chỉ đọc được clipboard khi app có focus
    }
    // ── Countdown ────────────────────────────────────────────────────────────

    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        setResendEnabled(false)

        val brandColor = com.google.android.material.color.MaterialColors.getColor(
            requireContext(),R.attr.textSecondary, 0xFF6C63FF.toInt()
        )
        binding.tvCountdown.setTextColor(brandColor)

        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            for (remaining in seconds downTo 0) {
                binding.tvCountdown.text = "%02d:%02d".format(remaining / 60, remaining % 60)
                if (remaining <= 30) {
                    binding.tvCountdown.setTextColor(
                        ContextCompat.getColor(requireContext(), android.R.color.holo_red_light)
                    )
                }
                if (remaining == 0) break
                delay(1000)
            }
            binding.tvCountdown.text = "Đã hết hạn"
            setResendEnabled(true)
        }
    }

    private fun setResendEnabled(enabled: Boolean) {
        binding.tvResend.isEnabled = enabled
        val brandColor = com.google.android.material.color.MaterialColors.getColor(
            requireContext(), R.attr.textPrimary, 0xFF6C63FF.toInt()
        )
        binding.tvResend.setTextColor(
            if (enabled) brandColor
            else ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
        )
    }

    // ── 6 OTP boxes ──────────────────────────────────────────────────────────

    private fun setupOtpBoxes() {
        otpBoxes.forEachIndexed { index, box ->
            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1) {
                        if (index < otpBoxes.size - 1) {
                            otpBoxes[index + 1].requestFocus()
                        } else {
                            hideKeyboard()
                            autoVerifyIfComplete()
                        }
                    }
                }
            })

            // Backspace → lùi ô
            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL
                    && event.action == KeyEvent.ACTION_DOWN
                    && box.text.isEmpty() && index > 0
                ) {
                    otpBoxes[index - 1].apply { requestFocus(); setText("") }
                    true
                } else false
            }

            box.setOnFocusChangeListener { _, hasFocus ->
                box.setBackgroundResource(
                    if (hasFocus) R.drawable.bg_otp_box_active
                    else R.drawable.bg_otp_box_normal
                )
            }
        }
        otpBoxes[0].requestFocus()
    }

    private fun getOtpValue() = otpBoxes.joinToString("") { it.text.toString() }

    private fun fillOtp(code: String) {
        val digits = code.filter { it.isDigit() }.take(6)
        digits.forEachIndexed { i, c -> otpBoxes[i].setText(c.toString()) }
        if (digits.length >= 6) {
            otpBoxes[5].requestFocus()
            hideKeyboard()
//            autoVerifyIfComplete()
        } else if (digits.isNotEmpty()) {
            otpBoxes[digits.length.coerceAtMost(5)].requestFocus()
        }
    }

    private fun clearOtpBoxes() {
        otpBoxes.forEach { it.setText("") }
        otpBoxes[0].requestFocus()
    }

    private fun autoVerifyIfComplete() {
        if (getOtpValue().length == 6) {
            isVerifying = true
            setLoading(true)
            viewModel.verifyOtp(
                email    = args.email,
                purpose  = args.purpose,
                otp      = getOtpValue(),
                username = args.username,
                password = args.password
            )
        }
    }

    // ── Clipboard ─────────────────────────────────────────────────────────────

    private fun checkClipboard() {
        clipboardManager = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        tryShowPasteHint()

        // Chỉ đăng ký 1 lần, tránh duplicate listener mỗi lần onResume
        if (clipboardListener == null) {
            clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
                tryShowPasteHint()
            }
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener!!)
        }
    }

    private fun tryShowPasteHint() {
        val binding = _binding ?: return  // guard null — tránh crash khi xoay màn hình
        val text = clipboardManager?.primaryClip
            ?.getItemAt(0)?.text?.toString() ?: return
        val digits = text.filter { it.isDigit() }
        if (digits.length == 6) {
            binding.tvPasteHint.isVisible = true
            binding.tvPasteHint.setOnClickListener {
                fillOtp(digits)
                binding.tvPasteHint.isVisible = false
            }
        } else {
            binding.tvPasteHint.isVisible = false
        }
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.btnVerify.setOnClickListener {
            if (isVerifying) return@setOnClickListener  // ← Thêm dòng này

            val otp = getOtpValue()
            if (otp.length != 6) {
                showError("Vui lòng nhập đủ 6 chữ số")
                return@setOnClickListener
            }

            isVerifying = true  // ← Set flag ngay lập tức
            setLoading(true)     // ← Disable UI

            viewModel.verifyOtp(
                email    = args.email,
                purpose  = args.purpose,
                otp      = otp,
                username = args.username,
                password = args.password
            )
        }

        binding.tvResend.setOnClickListener {
            if (!binding.tvResend.isEnabled) return@setOnClickListener
            clearOtpBoxes()
            binding.tvPasteHint.isVisible = false
            viewModel.resendOtp(args.email, args.purpose)
            startCountdown(OTP_TTL_SECONDS)
            showError("Đã gửi lại mã mới")
        }
    }

    // ── Observe ───────────────────────────────────────────────────────────────

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is OtpViewModel.UiState.Idle    -> {
                            setLoading(false)
                            isVerifying = false
                        }
                        is OtpViewModel.UiState.Loading -> setLoading(true)

                        is OtpViewModel.UiState.RegisterSuccess -> {
                            setLoading(false)
                            viewModel.resetState()
                            (requireActivity() as AuthActivity).navigateToMain()
                        }

                        is OtpViewModel.UiState.ResetOtpVerified -> {
                            setLoading(false)
                            viewModel.resetState()
                            findNavController().navigate(
                                OtpFragmentDirections.actionOtpToFpNewPassword(
                                    email = state.email,
                                    resetToken   = state.resetToken
                                )
                            )
                        }

                        is OtpViewModel.UiState.Error -> {
                            setLoading(false)
                            isVerifying = false
                            shakeOtpBoxes()
                            clearOtpBoxes()
                            showError(state.message)
                            viewModel.resetState()
                        }
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setLoading(show: Boolean) {
        binding.progressBar.isVisible = show
        binding.btnVerify.isEnabled   = !show
        binding.btnVerify.alpha       = if (show) 0.7f else 1f
        otpBoxes.forEach { it.isEnabled = !show }
    }

    private fun showError(msg: String) {
        val binding = _binding ?: return
        binding.tvError.text = msg
        binding.tvError.isVisible = true
        binding.root.postDelayed({
            _binding?.tvError?.isVisible = false  // ← dòng 290, đổi binding thành _binding?
        }, 3000)
    }

    private fun shakeOtpBoxes() {
        val shake = android.view.animation.AnimationUtils
            .loadAnimation(requireContext(), R.anim.shake)
        binding.otpContainer.startAnimation(shake)
    }

    private fun hideKeyboard() {
        val imm = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        requireActivity().currentFocus
            ?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onDestroyView() {
        countdownJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}