package com.example.autochat.ui.phone.fragment

import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.autochat.AppState
import com.example.autochat.databinding.FragmentProfileBinding
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.repository.AuthRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private var countdown: CountDownTimer? = null

    private val authRepository: AuthRepository by lazy {
        EntryPointAccessors.fromApplication(
            requireContext().applicationContext,
            ChatEntryPoint::class.java
        ).authRepository()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Hiện thông tin user
        binding.tvUsername.text = AppState.username
        binding.tvEmail.text = AppState.currentUserId

        // Tạo PIN
        binding.btnGetPin.setOnClickListener { generatePin() }

        // Đăng xuất
        binding.btnLogout.setOnClickListener {
            lifecycleScope.launch {
                authRepository.logout()
                AppState.logout()
                requireActivity().recreate()
            }
        }
    }

    private fun generatePin() {
        binding.btnGetPin.isEnabled = false
        binding.btnGetPin.text = "Dang tao..."

        lifecycleScope.launch {
            try {
                val pin = authRepository.generatePin()
                binding.pinCard.visibility = View.VISIBLE
                binding.tvPin.text = pin.toCharArray().joinToString("  ")

                countdown?.cancel()
                countdown = object : CountDownTimer(300_000, 1000) {
                    override fun onTick(ms: Long) {
                        val m = ms / 60000
                        val s = (ms % 60000) / 1000
                        binding.tvPinExpiry.text = "Het han sau %d:%02d".format(m, s)
                    }
                    override fun onFinish() {
                        binding.tvPin.text = "- - - - - -"
                        binding.tvPinExpiry.text = "PIN da het han"
                        binding.btnGetPin.isEnabled = true
                        binding.btnGetPin.text = "Tao PIN moi"
                    }
                }.start()

                binding.root.postDelayed({
                    binding.btnGetPin.isEnabled = true
                    binding.btnGetPin.text = "Tao PIN moi"
                }, 5000)

            } catch (e: Exception) {
                binding.btnGetPin.isEnabled = true
                binding.btnGetPin.text = "Tao PIN cho xe"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countdown?.cancel()
        _binding = null
    }
}