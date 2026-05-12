package com.example.autochat.ui.phone.fragment

import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.core.view.isVisible
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.autochat.R
import com.example.autochat.databinding.FragmentLoginBinding
import com.example.autochat.ui.phone.AuthActivity
import com.example.autochat.ui.phone.GoogleSignInHelper
import com.example.autochat.ui.phone.viewmodel.LoginViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LoginFragment : Fragment(R.layout.fragment_login) {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LoginViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentLoginBinding.bind(view)

        setupClickListeners()
        observeUiState()
    }

    private fun setupClickListeners() {
        binding.btnLogin.setOnClickListener {
            val email    = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            when {
                email.isEmpty()  -> showError("Vui lòng nhập email")
                !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    showError("Email không hợp lệ")
                password.length < 6 -> showError("Mật khẩu ít nhất 6 ký tự")
                else -> viewModel.login(email, password)
            }
        }

        binding.btnGoogle.setOnClickListener { launchGoogleSignIn() }

        binding.tvForgotPassword.setOnClickListener {
            findNavController().navigate(R.id.action_login_to_fp_email)
        }

        binding.tvGoRegister.setOnClickListener {
            findNavController().navigate(R.id.action_login_to_register)
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is LoginViewModel.UiState.Idle    -> setLoading(false)
                        is LoginViewModel.UiState.Loading -> setLoading(true)
                        is LoginViewModel.UiState.Success -> {
                            setLoading(false)
                            (requireActivity() as AuthActivity).navigateToMain()
                        }
                        is LoginViewModel.UiState.Error   -> {
                            setLoading(false)
                            showError(state.message)
                            viewModel.resetState()
                        }
                    }
                }
            }
        }
    }

    private fun launchGoogleSignIn() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                android.util.Log.d("GOOGLE", "Bắt đầu getIdToken")
                val idToken = GoogleSignInHelper.getIdToken(requireContext())
                android.util.Log.d("GOOGLE", "idToken: $idToken")
                viewModel.loginWithGoogle(idToken)
            } catch (e: GetCredentialCancellationException) {
                android.util.Log.d("GOOGLE", "User hủy")
            } catch (e: Exception) {
                android.util.Log.e("GOOGLE", "Lỗi: ${e.javaClass.name}: ${e.message}", e)
                showError("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun setLoading(show: Boolean) {
        binding.progressBar.isVisible = show
        binding.btnLogin.isEnabled  = !show
        binding.btnGoogle.isEnabled = !show
        binding.btnLogin.alpha      = if (show) 0.7f else 1f
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.isVisible = true
        binding.root.postDelayed({ binding.tvError.isVisible = false }, 3000)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}