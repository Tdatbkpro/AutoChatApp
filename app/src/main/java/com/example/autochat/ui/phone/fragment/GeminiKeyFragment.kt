package com.example.autochat.ui.phone.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.autochat.R
import com.example.autochat.databinding.FragmentGeminiKeyBinding
import com.example.autochat.ui.phone.viewmodel.ModelViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class GeminiKeyFragment : Fragment() {

    private var _binding: FragmentGeminiKeyBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ModelViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGeminiKeyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeStatus()
        setupClickableLinks()
        viewModel.fetchGeminiKeyStatus()
        setupClickListeners()
    }

    private fun setupClickableLinks() {
        binding.tvLinkAIStudio.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com"))
            startActivity(intent)
        }
    }

    private fun observeStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.geminiKeyStatus.collect { status ->
                val hasKey = status?.hasKey == true
                if (hasKey) {
                    binding.layoutKeyStatus.setCardBackgroundColor(0xFF1A2A1A.toInt())
                    binding.ivKeyStatusIcon.setImageResource(R.drawable.ic_check_circle)
                    binding.ivKeyStatusIcon.setColorFilter(0xFF4CAF50.toInt())
                    binding.tvKeyStatus.text = status.maskedKey
                        ?.let { "Key: $it" }
                        ?: "API key đã lưu trên server ✓"
                    binding.tvKeyStatus.setTextColor(0xFFFFFFFF.toInt())
                    binding.btnClear.visibility = View.VISIBLE
                    binding.etKey.setText("")
                    binding.etKey.hint = "Nhập key mới để thay thế..."
                } else {
                    binding.layoutKeyStatus.setCardBackgroundColor(0xFF2A1A1A.toInt())
                    binding.ivKeyStatusIcon.setImageResource(R.drawable.ic_warning)
                    binding.ivKeyStatusIcon.setColorFilter(0xFFFF6B6B.toInt())
                    binding.tvKeyStatus.text = "Chưa có API key"
                    binding.tvKeyStatus.setTextColor(0xFFFFFFFF.toInt())
                    binding.btnClear.visibility = View.GONE
                    binding.etKey.hint = "Nhập API key AIza..."
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.geminiKeyLoading.collect { loading ->
                binding.btnSave.isEnabled = !loading
                binding.btnClear.isEnabled = !loading
                binding.btnTestKey.isEnabled = !loading
                binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.geminiKeyError.collect { error ->
                error?.let {
                    Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                    viewModel.clearGeminiKeyError()
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnSave.setOnClickListener {
            val key = binding.etKey.text?.toString()?.trim()
            if (!key.isNullOrBlank()) {
                if (!isValidGeminiKey(key)) {
                    Toast.makeText(requireContext(),
                        "API key không hợp lệ (phải bắt đầu bằng AIza)",
                        Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                testThenSave(key)
            } else {
                Toast.makeText(requireContext(), "Vui lòng nhập API key", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClear.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Xóa API key")
                .setMessage("Bạn có chắc muốn xóa Gemini API key khỏi server?")
                .setPositiveButton("Xóa") { _, _ ->
                    viewModel.deleteGeminiKey()
                    binding.etKey.setText("")
                    Toast.makeText(requireContext(), "Đã xóa API key", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }

        binding.btnTestKey.setOnClickListener {
            val key = binding.etKey.text?.toString()?.trim()
            if (key.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Vui lòng nhập API key trước", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testKeyOnly(key)
        }
    }

    private fun isValidGeminiKey(key: String) = key.startsWith("AIza") && key.length >= 30

    private fun testThenSave(key: String) {
        binding.btnSave.isEnabled = false
        binding.btnSave.text = "Đang kiểm tra..."

        viewLifecycleOwner.lifecycleScope.launch {
            val valid = withContext(Dispatchers.IO) { callGeminiValidate(key) }
            binding.btnSave.isEnabled = true
            binding.btnSave.text = "Lưu key"

            if (valid) {
                viewModel.saveGeminiKey(key)
                binding.etKey.setText("")
                Toast.makeText(requireContext(), "✅ Đang lưu lên server...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "❌ API key không hợp lệ với Google", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun testKeyOnly(key: String) {
        binding.btnTestKey.isEnabled = false
        binding.btnTestKey.icon = null
        binding.btnTestKey.text = "..."

        viewLifecycleOwner.lifecycleScope.launch {
            val valid = withContext(Dispatchers.IO) { callGeminiValidate(key) }
            binding.btnTestKey.isEnabled = true
            binding.btnTestKey.text = ""
            binding.btnTestKey.icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                requireContext(),
                R.drawable.ic_check_circle
            )
            val msg = if (valid) "✅ API key hợp lệ!" else "❌ API key không hợp lệ"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun callGeminiValidate(key: String): Boolean = try {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$key")
            .build()
        client.newCall(request).execute().isSuccessful
    } catch (e: Exception) {
        false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}