package com.example.autochat.ui.phone.adapter.com.example.autochat.ui.phone.fragment

import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.example.autochat.R
import com.example.autochat.databinding.FragmentFpNewPasswordBinding
import com.example.autochat.ui.phone.adapter.com.example.autochat.ui.phone.viewmodel.ForgotPasswordViewModel

import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FpNewPasswordFragment : Fragment(R.layout.fragment_fp_new_password) {

    private var _binding: FragmentFpNewPasswordBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ForgotPasswordViewModel by viewModels()
    private val args: FpNewPasswordFragmentArgs by navArgs()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentFpNewPasswordBinding.bind(view)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.btnConfirm.setOnClickListener {
            val newPw   = binding.etNewPassword.text.toString()
            val confirm = binding.etConfirmPassword.text.toString()
            when {
                newPw.length < 6 -> showError(getString(R.string.error_password_short))
                newPw != confirm  -> showError(getString(R.string.error_password_mismatch))
                else -> viewModel.resetPassword(args.email, args.resetToken, newPw)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is ForgotPasswordViewModel.UiState.Idle    -> setLoading(false)
                        is ForgotPasswordViewModel.UiState.Loading -> setLoading(true)
                        is ForgotPasswordViewModel.UiState.ResetSuccess -> {
                            setLoading(false)
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.success_reset_password),
                                Toast.LENGTH_SHORT
                            ).show()
                            findNavController().navigate(
                                R.id.action_fp_new_password_to_login
                            )
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
        binding.progressBar.isVisible  = show
        binding.btnConfirm.isEnabled   = !show
        binding.btnConfirm.alpha       = if (show) 0.7f else 1f
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.isVisible = true
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}