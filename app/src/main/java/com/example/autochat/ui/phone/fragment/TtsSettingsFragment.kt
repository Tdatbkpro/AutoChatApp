package com.example.autochat.ui.phone.fragment

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.R
import com.example.autochat.databinding.FragmentTtsSettingsBinding
import com.example.autochat.tts.TTSManager
import com.example.autochat.tts.TtsEngine
import com.example.autochat.tts.TtsPrefs
import com.example.autochat.tts.TtsSettings
import com.example.autochat.tts.TtsVoice
import com.example.autochat.tts.VieNeuMode
import com.example.autochat.ui.phone.adapter.VoiceAdapter
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class TtsSettingsFragment : BottomSheetDialogFragment() {

    interface AudioPlaybackListener {
        fun onPlayAudio(filePath: String, label: String)
    }
    var audioPlaybackListener: AudioPlaybackListener? = null

    private var _binding: FragmentTtsSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TtsSettingsViewModel by viewModels()
    private var selectedVoice: TtsVoice? = null
    private var currentEngine = TtsEngine.GOOGLE
    private var isCloneMode = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Wrap context với AppTheme để ?attr/ resolve được
        val themedInflater = inflater.cloneInContext(
            androidx.appcompat.view.ContextThemeWrapper(requireContext(), R.style.AppTheme)
        )
        _binding = FragmentTtsSettingsBinding.inflate(themedInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupVoiceList()
        setupRecording()
        setupPickAudio()
        setupSliders()
        setupEngineTabs()
        setupModeTabs()
        setupButtons()
        observeViewModel()
        viewModel.loadVoices()

        if (viewModel.recordedFilePath.value == null) {
            viewModel.settings.value?.refAudioPath?.let { path ->
                if (File(path).exists()) viewModel.recordedFilePath.value = path
            }
        }
    }

    // ── Engine tabs ───────────────────────────────────────────────────────────

    private fun setupEngineTabs() {
        val cards = listOf(binding.cardGoogle, binding.cardVieNeu, binding.cardEdge, binding.cardSystem)
        val engines = listOf(TtsEngine.GOOGLE, TtsEngine.VIENEU, TtsEngine.EDGE, TtsEngine.SYSTEM)

        selectEngine(viewModel.settings.value?.engine ?: TtsEngine.GOOGLE)

        cards.forEachIndexed { i, card ->
            card.setOnClickListener { selectEngine(engines[i]) }
        }
    }

    private fun selectEngine(engine: TtsEngine) {
        currentEngine = engine
        val cards = listOf(binding.cardGoogle, binding.cardVieNeu, binding.cardEdge, binding.cardSystem)
        val engines = listOf(TtsEngine.GOOGLE, TtsEngine.VIENEU, TtsEngine.EDGE, TtsEngine.SYSTEM)

        cards.forEachIndexed { i, card ->
            val isActive = engines[i] == engine
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 14f * resources.displayMetrics.density
                setColor(if (isActive) 0xFF0D2A2E.toInt() else 0xFF1A1A2E.toInt())
                setStroke(
                    (1.5f * resources.displayMetrics.density).toInt(),
                    if (isActive) 0xFF31B1BD.toInt() else 0xFF2A2A3E.toInt()
                )
            }
            card.background = bg
        }

        binding.layoutVieNeuSettings.visibility =
            if (engine == TtsEngine.VIENEU) View.VISIBLE else View.GONE
    }

    // ── Mode tabs ─────────────────────────────────────────────────────────────

    private fun setupModeTabs() {
        selectMode(viewModel.settings.value?.vieNeuMode == VieNeuMode.CLONE)
        binding.tabPreset.setOnClickListener { selectMode(false) }
        binding.tabClone.setOnClickListener  { selectMode(true) }
    }

    private fun selectMode(clone: Boolean) {
        isCloneMode = clone
        val teal = requireContext().getColor(R.color.teal_primary)
        val hint  = requireContext().getColor(R.color.dark_text_hint)
        binding.tabPreset.setTextColor(if (!clone) teal else hint)
        binding.tabClone.setTextColor(if (clone) teal else hint)
        binding.tabPreset.background = requireContext().getDrawable(
            if (!clone) R.drawable.bg_tab_active else android.R.color.transparent
        )
        binding.tabClone.background = requireContext().getDrawable(
            if (clone) R.drawable.bg_tab_active else android.R.color.transparent
        )
        binding.layoutPresetVoice.visibility = if (!clone) View.VISIBLE else View.GONE
        binding.layoutCloneVoice.visibility  = if (clone)  View.VISIBLE else View.GONE
    }

    // ── Sliders ───────────────────────────────────────────────────────────────

    private fun setupSliders() {
        val s = viewModel.settings.value ?: TtsSettings()

        binding.seekPitch.progress = s.pitch + 10
        binding.tvPitchValue.text  = s.pitch.toString()
        binding.seekPitch.setOnSeekBarChangeListener(simpleSeekListener {
            binding.tvPitchValue.text = (it - 10).toString()
        })

        binding.seekSpeed.progress = ((s.speed - 0.5f) / 0.05f).toInt()
        binding.tvSpeedValue.text  = "${"%.1f".format(s.speed)}x"
        binding.seekSpeed.setOnSeekBarChangeListener(simpleSeekListener {
            binding.tvSpeedValue.text = "${"%.1f".format(0.5f + it * 0.05f)}x"
        })

        binding.seekVolume.progress = s.volume
        binding.tvVolumeValue.text  = "${s.volume}%"
        binding.seekVolume.setOnSeekBarChangeListener(simpleSeekListener {
            binding.tvVolumeValue.text = "$it%"
        })
    }

    private fun simpleSeekListener(onChanged: (Int) -> Unit) =
        object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                onChanged(p)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        }

    // ── Voice list ────────────────────────────────────────────────────────────

    private fun setupVoiceList() {
        binding.rvPresetVoices.layoutManager = LinearLayoutManager(requireContext())
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private fun setupRecording() {
        binding.btnRecord.setOnClickListener {
            if (requireContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 201)
                return@setOnClickListener
            }
            viewModel.startRecording()
        }
        binding.btnStopRecord.setOnClickListener  { viewModel.stopRecording() }
        binding.btnPlayRecord.setOnClickListener  { viewModel.playRecordedAudio() }
        binding.btnDeleteRecord.setOnClickListener {
            viewModel.deleteRecordedAudio()
        }

        val blinkAnim = android.animation.ObjectAnimator
            .ofFloat(binding.viewRecordDot, "alpha", 1f, 0f).apply {
                duration = 500
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode  = android.animation.ValueAnimator.REVERSE
            }
        viewModel.isRecording.observe(viewLifecycleOwner) { recording ->
            binding.layoutRecording.visibility = if (recording) View.VISIBLE else View.GONE
            if (recording) blinkAnim.start()
            else { blinkAnim.cancel(); binding.viewRecordDot.alpha = 1f }
        }
    }

    // ── Pick audio ────────────────────────────────────────────────────────────

    private fun setupPickAudio() {
        val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult
            val mimeType = requireContext().contentResolver.getType(uri)
            val ext = when (mimeType) {
                "audio/mpeg", "audio/mp3" -> ".mp3"
                "audio/mp4", "audio/m4a"  -> ".m4a"
                "audio/ogg"               -> ".ogg"
                else                      -> ".wav"
            }
            val dest = File(requireContext().filesDir, "tts_ref_voice$ext")
            requireContext().contentResolver.openInputStream(uri)?.use { it.copyTo(dest.outputStream()) }
            viewModel.recordedFilePath.value = dest.absolutePath
        }
        binding.btnPickRefAudio.setOnClickListener { pickAudio.launch("audio/*") }
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnTestTts.setOnClickListener {
            applyCurrent()
            lifecycleScope.launch {
                viewModel.ttsManager.speak("Xin chào, đây là giọng nói thử nghiệm.")
            }
        }
        binding.btnSaveTts.setOnClickListener {
            applyCurrent()
            Toast.makeText(requireContext(), "Đã lưu cài đặt TTS ✓", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    private fun applyCurrent() {
        viewModel.saveSettings(
            engine            = currentEngine,
            vieNeuMode        = if (isCloneMode) VieNeuMode.CLONE else VieNeuMode.PRESET,
            selectedVoiceId   = selectedVoice?.id,
            selectedVoiceName = selectedVoice?.name,
            refText           = binding.etRefText.text.toString(),
            voiceName         = binding.etVoiceName.text.toString().takeIf { it.isNotBlank() },
            pitch             = binding.seekPitch.progress - 10,
            speed             = 0.5f + binding.seekSpeed.progress * 0.05f,
            volume            = binding.seekVolume.progress,
        )
    }

    // ── Observe ───────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.settings.observe(viewLifecycleOwner) { settings ->
            settings ?: return@observe
            selectEngine(settings.engine)
            selectMode(settings.vieNeuMode == VieNeuMode.CLONE)
            binding.etRefText.setText(settings.refText ?: "")
            binding.etVoiceName.setText(settings.voiceName ?: "")
        }

        viewModel.voicesLoading.observe(viewLifecycleOwner) { loading ->
            binding.tvVoicesLoading.visibility = if (loading) View.VISIBLE else View.GONE
        }
        viewModel.voicesError.observe(viewLifecycleOwner) { error ->
            error?.let {
                binding.tvVoicesLoading.text = it
                binding.tvVoicesLoading.visibility = View.VISIBLE
            }
        }
        viewModel.voices.observe(viewLifecycleOwner) { voices ->
            if (voices.isEmpty()) return@observe
            selectedVoice = voices.find { it.id == viewModel.settings.value?.presetVoiceId }
                ?: voices.first()
            binding.rvPresetVoices.adapter = VoiceAdapter(
                voices     = voices,
                selectedId = selectedVoice?.id,
                onSelect   = { voice -> selectedVoice = voice },
                onPreview  = { voice ->
                    lifecycleScope.launch {
                        viewModel.ttsManager.previewVoice(voice.id) { path ->
                            audioPlaybackListener?.onPlayAudio(path, voice.name)
                        }
                    }
                },
            )
        }

        viewModel.recordedFilePath.observe(viewLifecycleOwner) { path ->
            if (path != null && File(path).exists()) {
                val file = File(path)
                binding.tvRefAudioName.text = "🎙️ ${file.name} (${file.length() / 1024}KB)"
                binding.layoutRecordPreview.visibility = View.VISIBLE
            } else {
                binding.tvRefAudioName.text = "Chưa chọn file"
                binding.layoutRecordPreview.visibility = View.GONE
            }
        }

        viewModel.recordSeconds.observe(viewLifecycleOwner) { sec ->
            binding.tvRecordTimer.text =
                "${viewModel.formatSeconds(sec)} / ${viewModel.formatSeconds(10)}"
        }
        viewModel.transcript.observe(viewLifecycleOwner) { text ->
            if (text.isNotEmpty()) binding.etRefText.setText(text)
        }
        viewModel.transcribing.observe(viewLifecycleOwner) { loading ->
            binding.etRefText.hint =
                if (loading) "Đang nhận dạng..." else "Nội dung đã nói trong audio mẫu..."
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}