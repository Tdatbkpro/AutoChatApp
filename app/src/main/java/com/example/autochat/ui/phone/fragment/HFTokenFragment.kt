package com.example.autochat.ui.phone.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.autochat.R
import com.example.autochat.databinding.FragmentHfTokenBinding
import com.example.autochat.ui.phone.viewmodel.ModelViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class HFTokenFragment : Fragment() {

    private var _binding: FragmentHfTokenBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ModelViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHfTokenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeToken()
        setupClickListeners()
    }

    private fun observeToken() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.hfToken.collect { token ->
                val hasToken = !token.isNullOrBlank()

                if (hasToken) {
                    binding.layoutTokenStatus.setBackgroundColor(0xFF1E2A1E.toInt())
                    binding.ivTokenStatusIcon.setColorFilter(0xFF4CAF50.toInt())
                    binding.ivTokenStatusIcon.setImageResource(R.drawable.ic_check_circle)
                    binding.tvTokenStatus.text = "Token: ...${token.takeLast(8)}"
                    binding.tvTokenStatus.setTextColor(0xFFFFFFFF.toInt())
                    binding.btnClear.visibility = View.VISIBLE
                    binding.etToken.setText(token)
                } else {
                    binding.layoutTokenStatus.setBackgroundColor(0xFF2A1E1E.toInt())
                    binding.ivTokenStatusIcon.setColorFilter(0xFFFF6B6B.toInt())
                    binding.ivTokenStatusIcon.setImageResource(R.drawable.ic_warning)
                    binding.tvTokenStatus.text = "Chưa có token - Tải chậm"
                    binding.tvTokenStatus.setTextColor(0xFFFFFFFF.toInt())
                    binding.btnClear.visibility = View.GONE
                    binding.etToken.setText("")
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnSave.setOnClickListener {
            val token = binding.etToken.text?.toString()?.trim()
            if (!token.isNullOrBlank()) {
                if (!token.startsWith("hf_")) {
                    Toast.makeText(requireContext(), "Token phải bắt đầu bằng hf_", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                viewModel.saveHFToken(token)
                Toast.makeText(requireContext(), "Đã lưu token!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Vui lòng nhập token", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClear.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Xóa token")
                .setMessage("Bạn có chắc muốn xóa HuggingFace token?")
                .setPositiveButton("Xóa") { _, _ ->
                    viewModel.clearHFToken()
                    binding.etToken.setText("")
                    Toast.makeText(requireContext(), " Đã xóa token", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }

        binding.btnTestToken.setOnClickListener {
            val token = binding.etToken.text?.toString()?.trim()
            if (token.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Vui lòng nhập token trước", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!token.startsWith("hf_")) {
                Toast.makeText(requireContext(), "Token không hợp lệ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testToken(token)
        }
    }

    private fun testToken(token: String) {
        binding.btnTestToken.isEnabled = false
        binding.btnTestToken.text = "..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder()
                        .url("https://huggingface.co/api/whoami-v2")
                        .header("Authorization", "Bearer $token")
                        .build()

                    client.newCall(request).execute()
                }

                binding.btnTestToken.isEnabled = true
                binding.btnTestToken.text = "🧪"

                if (result.isSuccessful) {
                    Toast.makeText(requireContext(), "✅ Token hợp lệ!", Toast.LENGTH_SHORT).show()
                    // Tự động lưu nếu test thành công
                    viewModel.saveHFToken(token)
                } else {
                    Toast.makeText(requireContext(), "❌ Token không hợp lệ (${result.code})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                binding.btnTestToken.isEnabled = true
                binding.btnTestToken.text = "🧪"
                Toast.makeText(requireContext(), "❌ Lỗi kết nối: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}