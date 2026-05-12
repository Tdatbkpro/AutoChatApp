package com.example.autochat.ui.phone.fragment

import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.autochat.R
import com.example.autochat.databinding.FragmentRegisterBinding
import com.example.autochat.ui.phone.viewmodel.RegisterViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RegisterFragment : Fragment(R.layout.fragment_register) {

    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RegisterViewModel by viewModels()

    // Lưu tạm để truyền sang OtpFragment sau khi gửi OTP thành công
    private var pendingUsername = ""
    private var pendingPassword = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentRegisterBinding.bind(view)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.btnRegister.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val email    = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            when {
                username.isEmpty()    -> showError("Vui lòng nhập tên hiển thị")
                username.length < 3   -> showError("Tên hiển thị ít nhất 3 ký tự")
                email.isEmpty()       -> showError("Vui lòng nhập email")
                !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    showError("Email không hợp lệ")
                password.length < 6   -> showError("Mật khẩu ít nhất 6 ký tự")
                else -> {
                    pendingUsername = username
                    pendingPassword = password
                    viewModel.sendRegisterOtp(email, username, password)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is RegisterViewModel.UiState.Idle    -> setLoading(false)
                        is RegisterViewModel.UiState.Loading -> setLoading(true)
                        is RegisterViewModel.UiState.OtpSent -> {
                            setLoading(false)
                            viewModel.resetState()
                            // Truyền email + purpose sang OtpFragment
                            val action = RegisterFragmentDirections.actionRegisterToOtp(
                                email    = state.email,
                                purpose  = "register",
                                username = pendingUsername,   // đã lưu ở bước validate
                                password = pendingPassword
                            )
                            findNavController().navigate(action)
                        }
                        is RegisterViewModel.UiState.Error   -> {
                            setLoading(false)
                            showError(state.message)
                            viewModel.resetState()
                        }
                    }
                }
            }
        }
    }

    private fun setLoading(show: Boolean) {
        binding.progressBar.isVisible  = show
        binding.btnRegister.isEnabled  = !show
        binding.btnRegister.alpha      = if (show) 0.7f else 1f
        binding.btnRegister.text       =
            if (show) "Đang gửi mã..." else "Gửi mã xác thực"
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.isVisible = true
        binding.root.postDelayed({ binding.tvError.isVisible = false }, 3000)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}