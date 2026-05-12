package com.example.autochat.ui.phone.adapter.com.example.autochat.ui.phone.fragment
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
import com.example.autochat.databinding.FragmentFpEmailBinding
import com.example.autochat.ui.phone.adapter.com.example.autochat.ui.phone.viewmodel.ForgotPasswordViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
@AndroidEntryPoint
class FpEmailFragment : Fragment(R.layout.fragment_fp_email) {

    private var _binding: FragmentFpEmailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ForgotPasswordViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentFpEmailBinding.bind(view)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.btnSend.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            when {
                email.isEmpty() -> showError("Vui lòng nhập email")
                !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    showError("Email không hợp lệ")
                else -> viewModel.sendResetOtp(email)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is ForgotPasswordViewModel.UiState.Idle    -> setLoading(false)
                        is ForgotPasswordViewModel.UiState.Loading -> setLoading(true)
                        is ForgotPasswordViewModel.UiState.OtpSent -> {
                            setLoading(false)
                            viewModel.resetState()
                            // Dùng chung OtpFragment với purpose = "reset_password"
                            val action = FpEmailFragmentDirections
                                .actionFpEmailToOtp(
                                    email   = state.email,
                                    purpose = "reset_password"
                                )
                            findNavController().navigate(action)
                        }
                        is ForgotPasswordViewModel.UiState.Error -> {
                            setLoading(false)
                            showError(state.message)
                            viewModel.resetState()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun setLoading(show: Boolean) {
        binding.progressBar.isVisible = show
        binding.btnSend.isEnabled     = !show
        binding.btnSend.alpha         = if (show) 0.7f else 1f
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.isVisible = true
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}